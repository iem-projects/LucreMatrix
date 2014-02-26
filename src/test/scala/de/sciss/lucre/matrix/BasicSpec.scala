package de.sciss.lucre.matrix

import org.scalatest.{fixture, Outcome, Matchers}
import de.sciss.lucre.event.Durable
import de.sciss.lucre.stm.store.BerkeleyDB
import Implicits._
import de.sciss.lucre.synth.expr.ExprImplicits

class BasicSpec extends fixture.FlatSpec with Matchers {
  final type S = Durable
  final type FixtureParam = Durable

  final def withFixture(test: OneArgTest): Outcome = {
    val system = Durable(BerkeleyDB.tmp())
    try {
      test(system)
    }
    finally {
      system.close()
    }
  }

  "A Zeros Matrix" should "sing while you sell" in { cursor =>
    val z = cursor.step { implicit tx =>
      val _z = Matrix.zeros(13, 21)
      assert(_z.rank         === 2)
      assert(_z.reducedRank  === 2)
      assert(_z.shape        === Vec(13, 21))
      assert(_z.reducedShape === Vec(13, 21))
      tx.newHandle(_z)
    }

    val m1 = cursor.step { implicit tx =>
      val _m1 = z().slice(0, 10)
      assert(_m1.rank         === 2)
      assert(_m1.reducedRank  === 1)
      assert(_m1.shape        === Vec( 1, 21))
      assert(_m1.reducedShape === Vec    (21))
      tx.newHandle(_m1)
    }

    val imp = ExprImplicits[S]
    import imp._

    val (mv, si, sv, oi, ov) = cursor.step { implicit tx =>
      val _mv = Matrix.Var(z())
      val _si = Ints.newVar[S](0)
      val _sv = Dimension.Selection.Var(Dimension.Selection.Index(_si))
      val _oi = Ints.newVar[S](0)
      val _ov = Reduce.Op.Var(Reduce.Op.Apply(_oi))
      implicit val intVar = Ints.varSerializer[S]
      (tx.newHandle(_mv), tx.newHandle(_si), tx.newHandle(_sv), tx.newHandle(_oi), tx.newHandle(_ov))
    }

    val m2 = cursor.step { implicit tx =>
      val _m2 = Reduce(mv(), sv(), ov())
      tx.newHandle(_m2)
    }

    cursor.step { implicit tx =>
      z ().dispose()
      m1().dispose()
      m2().dispose()
    }
  }
}
