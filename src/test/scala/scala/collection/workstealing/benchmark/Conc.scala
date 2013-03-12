package scala.collection.workstealing
package benchmark



import annotation.tailrec



object ConcUtils {

  def create(sz: Int): Conc[Int] = sz match {
    case 0 => Conc.Nil
    case 1 => Conc.Single(1)
    case _ => create(sz / 2) || create(sz - sz / 2)
  }

}


object ConcFold extends StatisticsBenchmark {

  val size = sys.props("size").toInt
  val conc = ConcUtils.create(size)

  def run() {
    conc.fold(0)(_ + _)
  }

}


object ConcFoldGeneric extends StatisticsBenchmark {

  val size = sys.props("size").toInt
  val conc: ParIterable[Int] = ConcUtils.create(size)

  def run() {
    conc.fold(0)(_ + _)
  }

}


object ConcFoldRecursion extends StatisticsBenchmark {

  val size = sys.props("size").toInt
  val conc = ConcUtils.create(size)

  def run() {
    import Conc._
    def fold(c: Conc[Int]): Int = c match {
      case left || right => fold(left) + fold(right)
      case Nil() => 0
      case Single(elem) => elem
    }
    fold(conc)
  }

}


object ListFold extends StatisticsBenchmark {

  val size = sys.props("size").toInt
  val list = (0 until size).toList

  def run() {
    list.fold(0)(_ + _)
  }

}


object ConcFoldExpensive extends StatisticsBenchmark {

  val size = sys.props("size").toInt
  val conc = ConcUtils.create(size)

  def run() {
    conc.fold(0) {
      (x, y) => x + MathUtils.taylor(y).toInt
    }
  }

}


