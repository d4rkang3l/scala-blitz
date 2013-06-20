package scala.collection.parallel
package workstealing



import scala.annotation.tailrec
import scala.reflect.ClassTag



trait TreeStealer[T, N >: Null <: AnyRef] extends Stealer[T] {
  import TreeStealer._

  val root: N
  val totalSize: Int
  val classTag: ClassTag[N]
  val pathStack = new Array[Int](cacheAligned(depthBound(totalSize) + 1))
  val nodeStack = classTag.newArray(cacheAligned(depthBound(totalSize) + 1))
  var depth: Int = 0

  def totalChildren(n: N): Int
  def child(n: N, idx: Int): N
  def isLeaf(n: N): Boolean
  def estimateSubtree(n: N, depth: Int, totalSize: Int): Int
  def depthBound(totalSize: Int): Int

  private def checkBounds(idx: Int) = {
    if (idx < 0 || idx >= pathStack.length) throw new IndexOutOfBoundsException("idx: " + idx + ", length: " + pathStack.length)
  }

  final def OFFSET(idx: Int): Int = STACK_BASE_OFFSET + idx * STACK_INDEX_SCALE

  final def READ_STACK(idx: Int): Int = {
    checkBounds(idx)
    unsafe.getIntVolatile(pathStack, OFFSET(idx))
  }

  final def WRITE_STACK(idx: Int, nv: Int) {
    checkBounds(idx)
    unsafe.putIntVolatile(pathStack, OFFSET(idx), nv)
  }

  final def CAS_STACK(idx: Int, ov: Int, nv: Int): Boolean = {
    checkBounds(idx)
    unsafe.compareAndSwapInt(pathStack, OFFSET(idx), ov, nv)
  }

  final def encodeStolen(origin: Int, progress: Int): Int = {
    SPECIAL_MASK |
    STOLEN_MASK |
    ((origin << ORIGIN_SHIFT) & ORIGIN_MASK) |
    ((progress << PROGRESS_SHIFT) & PROGRESS_MASK)
  }

  final def encodeCompleted(origin: Int, progress: Int): Int = {
    SPECIAL_MASK |
    COMPLETED_MASK |
    ((origin << ORIGIN_SHIFT) & ORIGIN_MASK) |
    ((progress << PROGRESS_SHIFT) & PROGRESS_MASK)
  }

  final def encode(origin: Int, progress: Int): Int = {
    SPECIAL_MASK |
    ((origin << ORIGIN_SHIFT) & ORIGIN_MASK) |
    ((progress << PROGRESS_SHIFT) & PROGRESS_MASK)
  }

  final def completed(code: Int): Boolean = ((code & COMPLETED_MASK) >>> COMPLETED_SHIFT) != 0

  final def stolen(code: Int): Boolean = ((code & STOLEN_MASK) >>> STOLEN_SHIFT) != 0

  final def origin(code: Int): Int = ((code & ORIGIN_MASK) >>> ORIGIN_SHIFT)

  final def progress(code: Int): Int = ((code & PROGRESS_MASK) >>> PROGRESS_SHIFT)

  final def special(code: Int): Boolean = (code & SPECIAL_MASK) != 0

  final def decodeString(code: Int): String = if (special(code)) {
    "([%s%s] %d, %d)".format(
      if (completed(code)) "C" else " ",
      if (stolen(code)) "!" else " ",
      origin(code),
      progress(code)
    )
  } else code.toString

  final def push(ov: Int, nv: Int): Boolean = if (CAS_STACK(depth + 1, ov, nv)) {
    val cidx = origin(nv)
    nodeStack(depth + 1) = child(nodeStack(depth), cidx)
    depth += 1
    true
  } else false

  final def pop(ov: Int, nv: Int): Boolean = if (CAS_STACK(depth, ov, nv)) {
    nodeStack(depth) = null
    depth -= 1
    true
  } else false

  final def switch(ov: Int, nv: Int): Boolean = if (CAS_STACK(depth, ov, nv)) {
    true
  } else false

  final def peekprev = if (depth > 0) READ_STACK(depth - 1) else 0

  final def peekcurr = READ_STACK(depth)

  final def peeknext = READ_STACK(depth + 1)

}


object TreeStealer {

  val STACK_BASE_OFFSET = unsafe.arrayBaseOffset(classOf[Array[Int]])
  val STACK_INDEX_SCALE = unsafe.arrayIndexScale(classOf[Array[Int]])
  val PROGRESS_SHIFT = 0
  val PROGRESS_MASK = 0x00000fff
  val ORIGIN_SHIFT = 12
  val ORIGIN_MASK = 0x00fff000
  val STOLEN_SHIFT = 24
  val STOLEN_MASK = 0x01000000
  val COMPLETED_SHIFT = 25
  val COMPLETED_MASK = 0x02000000
  val SPECIAL_SHIFT = 31
  val SPECIAL_MASK = 0x80000000

  def cacheAligned(sz: Int) = ((sz - 1) / 16 + 1) * 16

  trait ChunkIterator[@specialized(Int, Long, Float, Double) T] {
    def hasNext: Boolean
    def next(): T
  }

  trait External[@specialized(Int, Long, Float, Double) T, N >: Null <: AnyRef] extends TreeStealer[T, N] {
    val chunkIterator: ChunkIterator[T]

    def resetIterator(n: N): Unit

    def elementAt(n: N, idx: Int): T

    def rootInit() {
      pathStack(0) = encode(1, 1)
      nodeStack(0) = root
    }

    def hasNext = chunkIterator.hasNext

    def next() = chunkIterator.next()

    final def state = {
      val code = READ_STACK(0)
      if (completed(code)) Stealer.Completed
      else if (stolen(code)) Stealer.StolenOrExpanded
      else Stealer.AvailableOrOwned
    }

    @tailrec final def advance(step: Int): Int = if (depth < 0) -1 else {
      def isLast(prev: Int, curr: Int) = prev == 0 || {
        val prevnode = nodeStack(depth - 1)
        totalChildren(prevnode) == origin(curr)
      }

      val next = peeknext
      val curr = peekcurr
      val prev = peekprev

      if (completed(curr)) {
        -1
      } else if (special(prev) && stolen(prev) || stolen(curr)) {
        markStolen()
        -1
      } else {
        val currnode = nodeStack(depth)
        val totalch = totalChildren(currnode)
        val currprogress = progress(curr)
        if (currprogress < totalch || totalch == 0) {
          if (next == 0) {
            val estimate = estimateSubtree(currnode, depth, totalSize)
            if (isLeaf(currnode) || estimate < step) {
              // decide to batch - pop to completed
              val last = isLast(prev, curr)
              val ncurr = if (last) {
                if (depth > 0) 0 else encodeCompleted(origin(curr), 0)
              } else encode(progress(prev), 0)
              if (pop(curr, ncurr)) {
                resetIterator(currnode)
                estimate
              } else advance(step)
            } else {
              // or not to batch - push
              val nnext = encode(currprogress, 1)
              push(next, nnext)
              advance(step)
            }
          } else {
            if (currprogress == origin(next)) {
              // next origin identical - switch
              val ncurr = encode(origin(curr), currprogress + 1)
              switch(curr, ncurr)
            } else {
              // next origin different - push
              val nnext = encode(currprogress, 1)
              push(next, nnext)
            }
            advance(step)
          }
        } else {
          if (next == 0) {
            val last = isLast(prev, curr)
            if (last) {
              if (depth > 0) {
                // last node below root - pop to 0
                pop(curr, 0)
              } else {
                // at root mark stealer as completed
                val ncurr = encodeCompleted(origin(curr), 0)
                pop(curr, ncurr)
              }
            } else {
              // not last - pop to completed
              val ncurr = encode(origin(curr), 0)
              pop(curr, ncurr)
            }
            advance(step)
          } else {
            if (currprogress == origin(next)) {
              // next origin identical - error!
              sys.error("error state!")
            } else {
              // next origin different - push
              val nnext = encode(currprogress, 1)
              push(next, nnext)
            }
            advance(step)
          }
        }
      }
    }

    @tailrec final def markCompleted(): Boolean = {
      val code = READ_STACK(0)
      if (stolen(code)) {
        markStolen()
        false
      } else {
        val ncode = encodeCompleted(origin(code), progress(code))
        if (CAS_STACK(0, code, ncode)) true
        else markCompleted()
      }
    }

    final def markStolen(): Boolean = ???

    def split: (Stealer[T], Stealer[T]) = ???

    def elementsRemainingEstimate: Int = ???

    override def toString = "TreeStealer.External(\ndepth: %d\npath:  %s\nnodes: %s\n)".format(
      depth,
      pathStack.map(x => "%1$11s".format(decodeString(x))).mkString(", "),
      nodeStack map {
        case null => null
        case x => x.getClass.getSimpleName.takeRight(4) + "@" + System.identityHashCode(x).toString.takeRight(4)
      } map {
        case s => "%1$11s".format(s)
      } mkString(", ")
    )

  }

}




