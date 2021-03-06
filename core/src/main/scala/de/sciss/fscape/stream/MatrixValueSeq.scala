/*
 *  MatrixValueSeq.scala
 *  (LucreMatrix)
 *
 *  Copyright (c) 2014-2017 Institute of Electronic Music and Acoustics, Graz.
 *  Copyright (c) 2014-2017 by Hanns Holger Rutz.
 *
 *	This software is published under the GNU Lesser General Public License v2.1+
 *
 *
 *	For further information, please contact Hanns Holger Rutz at
 *	contact@sciss.de
 */

package de.sciss.fscape
package stream

import akka.stream.{Attributes, SourceShape}
import de.sciss.fscape.stream.impl.{BlockingGraphStage, MatrixValueImpl, NodeImpl}
import de.sciss.lucre.matrix.Matrix

import scala.concurrent.Future

object MatrixValueSeq {
  def apply(matrix: Future[Matrix.Reader])(implicit b: Builder): OutD = {
    val source  = new Stage(matrix)
    val stage   = b.add(source)
    stage.out
  }

  private final val name = "MatrixValueSeq"

  private type Shape = SourceShape[BufD]

  private final class Stage(matrix: Future[Matrix.Reader])(implicit ctrl: Control)
    extends BlockingGraphStage[Shape](s"$name($matrix)") {

    val shape = SourceShape(OutD(s"$name.out"))

    def createLogic(attr: Attributes): NodeImpl[Shape] =
      new Logic(shape, matrix)
  }

  private final class Logic(shape: Shape, matrixF: Future[Matrix.Reader])(implicit ctrl: Control)
    extends MatrixValueImpl(name, shape, matrixF) {

    private[this] val bufSize: Int = ctrl.blockSize

    private[this] var framesRead  = 0L

    protected def process(matrix: Matrix.Reader): Unit = {
      val chunk = math.min(bufSize, matrix.size - framesRead).toInt
      if (chunk == 0) {
        logStream(s"completeStage() $this")
        completeStage()
      } else {
        val bufOut = ctrl.borrowBufD()
        matrix.readDouble1D(bufOut.buf, 0, chunk)
        bufOut.size = chunk   // IntelliJ highlight bug
        framesRead += chunk
        push(shape.out, bufOut)
      }
    }
  }
}