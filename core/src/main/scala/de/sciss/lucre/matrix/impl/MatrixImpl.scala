/*
 *  MatrixImpl.scala
 *  (LucreMatrix)
 *
 *  Copyright (c) 2014-2015 Institute of Electronic Music and Acoustics, Graz.
 *  Copyright (c) 2014-2015 by Hanns Holger Rutz.
 *
 *	This software is published under the GNU Lesser General Public License v2.1+
 *
 *
 *	For further information, please contact Hanns Holger Rutz at
 *	contact@sciss.de
 */

package de.sciss.lucre.matrix
package impl

import de.sciss.lucre.event.Targets
import de.sciss.lucre.stm.impl.ObjSerializer
import de.sciss.lucre.stm.{NoSys, Obj}
import de.sciss.serial.{DataInput, Serializer}

import scala.annotation.switch

object MatrixImpl {
  implicit def serializer[S <: Sys[S]]: Serializer[S#Tx, S#Acc, Matrix[S]] /* with evt.Reader[S, Matrix[S]] */ =
    anySer.asInstanceOf[Ser[S]]

  def readIdentifiedObj[S <: Sys[S]](in: DataInput, access: S#Acc)(implicit tx: S#Tx): Obj[S] = {
    val cookie = in.readByte()

    cookie match {
      case 0 =>
        val targets = Targets.readIdentified(in, access)

        def readVar()(implicit tx: S#Tx): Matrix.Var[S] =
          impl.MatrixVarImpl.readIdentified(in, access, targets)

        def readNode(): Matrix[S] /* with evt.Node[S] */ = {
          val tpe   = in.readInt()
          if (tpe != Matrix.typeID) sys.error(s"Unexpected type (found $tpe, expected ${Matrix.typeID})")
          val opID  = in.readInt()
          (opID: @switch) match {
            case Reduce.opID              => Reduce             .readIdentified        (in, access, targets)
//            case DataSource.Variable.opID => impl.DataSourceImpl.readIdentifiedVariable(in, access, targets)
            case _                        => sys.error(s"Unknown operator id $opID")
          }
        }

        // 0 = var, 1 = op
        (in.readByte(): @switch) match {
          case 0      => readVar ()
          case 1      => readNode()
          case other  => sys.error(s"Unexpected cookie $other")
        }

      case 3 =>
        val id    = tx.readID(in, access)
        val opID  = in.readInt()
        (opID: @switch) match {
          case impl.ZeroMatrixImpl .opID => impl.ZeroMatrixImpl .readIdentified(id, in)
          case impl.ConstMatrixImpl.opID => impl.ConstMatrixImpl.readIdentified(id, in)
          case DataSource.Variable .opID => impl.DataSourceImpl .readIdentifiedVariable(in, access, id /* targets */)
          case _                         => sys.error(s"Unexpected operator $opID")
        }

      case other => sys.error(s"Unexpected cookie $other")
    }
  }

  // ---- impl ----

  private val anySer = new Ser[NoSys]

  private final class Ser[S <: Sys[S]] extends ObjSerializer[S, Matrix[S]] {
    def tpe: Obj.Type = Matrix
  }
}