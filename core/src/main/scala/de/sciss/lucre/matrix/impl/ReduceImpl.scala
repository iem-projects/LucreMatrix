/*
 *  ReduceImpl.scala
 *  (LucreMatrix)
 *
 *  Copyright (c) 2014 Institute of Electronic Music and Acoustics, Graz.
 *  Written by Hanns Holger Rutz.
 *
 *	This software is published under the GNU Lesser General Public License v2.1+
 *
 *
 *	For further information, please contact Hanns Holger Rutz at
 *	contact@sciss.de
 */

package de.sciss.lucre
package matrix
package impl

import java.io.EOFException
import java.{util => ju}

import Reduce.Op
import de.sciss.file._
import de.sciss.lucre.matrix.DataSource.Resolver
import de.sciss.lucre.matrix.Matrix.Reader
import de.sciss.lucre.{event => evt}
import expr.Expr
import evt.EventLike
import Dimension.Selection
import ucar.{ma2, nc2}
import scala.annotation.{switch, tailrec}
import de.sciss.serial.{ImmutableSerializer, DataInput, DataOutput}
import de.sciss.lucre.matrix.Reduce.Op.Update
import scala.collection.{JavaConversions, breakOut}

object ReduceImpl {
  def apply[S <: Sys[S]](in : Matrix[S], dim: Selection[S], op: Op[S])(implicit tx: S#Tx): Reduce[S] = {
    val targets = evt.Targets[S]
    new Impl[S](targets, in, dim, op)
  }

  implicit def serializer[S <: Sys[S]]: evt.Serializer[S, Reduce[S]] = anySer.asInstanceOf[Ser[S]]

  private val anySer = new Ser[evt.InMemory]

  private final class Ser[S <: Sys[S]] extends evt.EventLikeSerializer[S, Reduce[S]] {
    def read(in: DataInput, access: S#Acc, targets: evt.Targets[S])(implicit tx: S#Tx): Reduce[S] = {
      val cookie = in.readByte() // 'node'
      if (cookie != 1) sys.error(s"Unexpected cookie (found $cookie, expected 1")
      val tpe     = in.readInt()  // 'type'
      if (tpe != Matrix.typeID) sys.error(s"Unexpected type id (found $tpe, expected ${Matrix.typeID}")
      val opID  = in.readInt()    // 'op'
      if (opID != Reduce.opID) sys.error(s"Unexpected operator id (found $opID, expected ${Reduce.opID})")
      readIdentified[S](in, access, targets)
    }

    def readConstant(in: DataInput)(implicit tx: S#Tx): Reduce[S] =
      sys.error("Unsupported constant reduce matrix")
  }

  def read[S <: Sys[S]](in: DataInput, access: S#Acc)(implicit tx: S#Tx): Reduce[S] = serializer[S].read(in, access)
  
  private[matrix] def readIdentified[S <: Sys[S]](in: DataInput, access: S#Acc, targets: evt.Targets[S])
                                         (implicit tx: S#Tx): Reduce[S] = {
    val matrix  = Matrix    .read(in, access)
    val dim     = Selection .read(in, access)
    val op      = Op        .read(in, access)
    new Impl(targets, matrix, dim, op)
  }

  implicit def opSerializer[S <: Sys[S]]: evt.Serializer[S, Op[S]] = anyOpSer.asInstanceOf[OpSer[S]]

  private val anyOpSer = new OpSer[evt.InMemory]

  implicit def opVarSerializer[S <: Sys[S]]: evt.Serializer[S, Op.Var[S]] = anyOpVarSer.asInstanceOf[OpVarSer[S]]

  private val anyOpVarSer = new OpVarSer[evt.InMemory]

  private final class OpSer[S <: Sys[S]] extends evt.EventLikeSerializer[S, Op[S]] {
    def read(in: DataInput, access: S#Acc, targets: evt.Targets[S])(implicit tx: S#Tx): Op[S] with evt.Node[S] = {
      (in.readByte(): @switch) match {
        case 0      => readIdentifiedOpVar(in, access, targets)
        case 1      => readNode(in, access, targets)
        case other  => sys.error(s"Unsupported cookie $other")
      }
    }

    private def readNode(in: DataInput, access: S#Acc, targets: evt.Targets[S])
                        (implicit tx: S#Tx): Op[S] with evt.Node[S] = {
      val tpe   = in.readInt()
      require(tpe == Op.typeID, s"Unexpected type id (found $tpe, expected ${Op.typeID})")
      val opID  = in.readInt()
      (opID: @switch) match {
        case Op.Apply.opID =>
          val index = expr.Int.read(in, access)
          new OpApplyImpl[S](targets, index)

        case Op.Slice.opID =>
          val from  = expr.Int.read(in, access)
          val to    = expr.Int.read(in, access)
          new OpSliceImpl[S](targets, from, to)

        case Op.Stride.opID =>
          val from  = expr.Int.read(in, access)
          val to    = expr.Int.read(in, access)
          val step  = expr.Int.read(in, access)
          new OpStrideImpl[S](targets, from = from, to = to, step = step)

        case _ => sys.error(s"Unsupported operator id $opID")
      }
    }

    def readConstant(in: DataInput)(implicit tx: S#Tx): Op[S] = sys.error("Unknown constant op")
  }

  private final class OpVarSer[S <: Sys[S]] extends evt.EventLikeSerializer[S, Op.Var[S]] {
    def read(in: DataInput, access: S#Acc, targets: evt.Targets[S])(implicit tx: S#Tx): Op.Var[S] = {
      val cookie = in.readByte()
      require(cookie == 0, s"Unexpected cookie (found $cookie, expected 0)")
      readIdentifiedOpVar(in, access, targets)
    }

    def readConstant(in: DataInput)(implicit tx: S#Tx): Op.Var[S] =
      sys.error("Unsupported constant op variable")
  }

  private def readIdentifiedOpVar[S <: Sys[S]](in: DataInput, access: S#Acc, targets: evt.Targets[S])
                                              (implicit tx: S#Tx): Op.Var[S] = {
    val ref = tx.readVar[Op[S]](targets.id, in)
    new OpVarImpl[S](targets, ref)
  }

  def applyOpVar[S <: Sys[S]](init: Op[S])(implicit tx: S#Tx): Reduce.Op.Var[S] = {
    val targets = evt.Targets[S]
    val ref     = tx.newVar(targets.id, init)
    new OpVarImpl[S](targets, ref)
  }

  def applyOpApply[S <: Sys[S]](index: Expr[S, Int])(implicit tx: S#Tx): Op.Apply[S] = {
    val targets = evt.Targets[S]
    new OpApplyImpl[S](targets, index)
  }

  def applyOpSlice[S <: Sys[S]](from: Expr[S, Int], to: Expr[S, Int])(implicit tx: S#Tx): Op.Slice[S] = {
    val targets = evt.Targets[S]
    new OpSliceImpl[S](targets, from = from, to = to)
  }

  def applyOpStride[S <: Sys[S]](from: Expr[S, Int], to: Expr[S, Int], step: Expr[S, Int])
                                (implicit tx: S#Tx): Op.Stride[S] = {
    val targets = evt.Targets[S]
    new OpStrideImpl[S](targets, from = from, to = to, step = step)
  }

  // ---- actual implementations ----

  private final class OpVarImpl[S <: Sys[S]](protected val targets: evt.Targets[S],
                                             protected val ref: S#Var[Op[S]])
    extends Op.Var[S] with VarImpl[S, Op.Update[S], Op[S], Op.Update[S]] {

    def size(in: Int)(implicit tx: S#Tx): Int = apply().size(in)

    protected def mapUpdate(in: Update[S]): Op.Update[S] = in.copy(op = this)

    protected def mkUpdate(before: Op[S], now: Op[S]): Op.Update[S] = Op.Update(this)

    protected def reader: evt.Reader[S, Op[S]] = Op.serializer
  }

  private sealed trait OpNativeImpl[S <: Sys[S]] extends evt.impl.StandaloneLike[S, Op.Update[S], Op[S]] {
    _: Op[S] =>

    // ---- abstract ----

    protected def writeOpData(out: DataOutput): Unit

    def map(in: Range)(implicit tx: S#Tx): Range

    // ---- impl ----

    final protected def writeData(out: DataOutput): Unit = {
      out writeByte 1 // cookie
      out writeInt Op.typeID
      writeOpData(out)
    }

    final protected def disposeData()(implicit tx: S#Tx) = ()

    // ---- event ----

    final def changed: EventLike[S, Op.Update[S]] = this

    final protected def reader: evt.Reader[S, Op[S]] = Op.serializer
  }

  private final class OpApplyImpl[S <: Sys[S]](protected val targets: evt.Targets[S],
                                               val index: Expr[S, Int])
    extends OpNativeImpl[S] with Op.Apply[S] {

    override def toString() = s"Apply$id($index)"

    def size(in: Int)(implicit tx: S#Tx): Int = math.min(in, 1)

    def map(in: Range)(implicit tx: S#Tx): Range = {
      val iv = index.value
      if (iv >= 0 && iv < in.size) {
        val x = in(iv)
        Range.inclusive(x, x)
      } else {
        Range(in.start, in.start) // empty -- which index to choose?
      }
    }

    protected def writeOpData(out: DataOutput): Unit = {
      // out writeByte 1 // cookie
      // out writeInt Op.typeID
      out writeInt Op.Apply.opID
      index write out
    }

    // ---- event ----

    def pullUpdate(pull: evt.Pull[S])(implicit tx: S#Tx): Option[Update[S]] =
      pull(index.changed).map(_ => Op.Update(this))

    def connect   ()(implicit tx: S#Tx): Unit = index.changed ---> this
    def disconnect()(implicit tx: S#Tx): Unit = index.changed -/-> this
  }

  private final class OpSliceImpl[S <: Sys[S]](protected val targets: evt.Targets[S],
                                               val from: Expr[S, Int], val to: Expr[S, Int])
    extends OpNativeImpl[S] with Op.Slice[S] {

    override def toString() = s"Slice$id($from, $to)"

    def size(in: Int)(implicit tx: S#Tx): Int = {
      val lo  = from .value
      val hi  = to   .value
      val lo1 = math.max(0, lo)
      val hi1 = math.min(in, hi + 1)
      val res = hi1 - lo1
      math.max(0, res)
    }

    def map(in: Range)(implicit tx: S#Tx): Range = {
      val lo  = from .value
      val hi  = to   .value
      val by  = lo to hi
      sampleRange(in, by)
    }

    protected def writeOpData(out: DataOutput): Unit = {
      // out writeByte 1   // cookie
      // out writeInt Op.typeID
      out writeInt Op.Slice.opID
      from  write out
      to    write out
    }

    // ---- event ----

    def pullUpdate(pull: evt.Pull[S])(implicit tx: S#Tx): Option[Op.Update[S]] = {
      val e0 =       pull.contains(from .changed) && pull(from .changed).isDefined
      val e1 = e0 || pull.contains(to   .changed) && pull(to   .changed).isDefined

      if (e1) Some(Op.Update(this)) else None
    }

    def connect()(implicit tx: S#Tx): Unit = {
      from .changed ---> this
      to   .changed ---> this
    }

    def disconnect()(implicit tx: S#Tx): Unit = {
      from .changed -/-> this
      to   .changed -/-> this
    }
  }

  private final class OpStrideImpl[S <: Sys[S]](protected val targets: evt.Targets[S],
                                                val from: Expr[S, Int], val to: Expr[S, Int], val step: Expr[S, Int])
    extends OpNativeImpl[S] with Op.Stride[S] {

    override def toString() = s"Stride$id($from, $to, $step)"

    def size(in: Int)(implicit tx: S#Tx): Int = {
      val lo  = from .value
      val hi  = to   .value
      val s   = step .value
      // note: in NetCDF, ranges must be non-negative, so
      // we don't check invalid cases here, but simply truncate.
      val lo1 = math.max(0, lo)
      val hi1 = math.min(in - 1, hi)
      val szm = hi1 - lo1
      val res = szm / s + 1
      math.max(0, res)
    }

    def map(in: Range)(implicit tx: S#Tx): Range = {
      val lo  = from .value
      val hi  = to   .value
      val s   = step .value
      val by  = lo to hi by s
      sampleRange(in, by)
    }

    protected def writeOpData(out: DataOutput): Unit = {
      // out writeByte 1   // cookie
      // out writeInt Op.typeID
      out writeInt Op.Stride.opID
      from  write out
      to    write out
      step  write out
    }

    // ---- event ----

    def pullUpdate(pull: evt.Pull[S])(implicit tx: S#Tx): Option[Op.Update[S]] = {
      val e0 =       pull.contains(from .changed) && pull(from .changed).isDefined
      val e1 = e0 || pull.contains(to   .changed) && pull(to   .changed).isDefined
      val e2 = e1 || pull.contains(step .changed) && pull(step .changed).isDefined

      if (e2) Some(Op.Update(this)) else None
    }

    def connect()(implicit tx: S#Tx): Unit = {
      from .changed ---> this
      to   .changed ---> this
      step .changed ---> this
    }

    def disconnect()(implicit tx: S#Tx): Unit = {
      from .changed -/-> this
      to   .changed -/-> this
      step .changed -/-> this
    }
  }

  private def mkReduceReaderFactory[S <: Sys[S]](r: Reduce[S], streamDim: Int)
                                                (implicit tx: S#Tx /* , resolver: DataSource.Resolver[S] */): Matrix.Key = {
    val idx   = r.indexOfDim
    val rInF  = r.in.getKey(streamDim) // mkReaderFactory(r.in, streamDim)

    @tailrec def loop(op: Reduce.Op[S]): Matrix.Key = op match {
      case op: OpNativeImpl[S] =>
        rInF match {
          case t: ReaderFactory.HasSection =>
            if (idx >= 0) t.section = t.section.updated(idx, op.map(t.section(idx)))
            t
          case t /* t: ReaderFactory.Opaque */ =>
            var section = mkAllRange(r.in.shape)
            if (idx >= 0) section = section.updated(idx, op.map(section(idx)))
            new ReaderFactory.Cloudy(t /*.source */, streamDim, section)
        }

      case op: Op.Var[S] => loop(op())

      case _ =>
        // val rd = op.map(rInF.reader(), r.in.shape, idx, streamDim)
        // new ReaderFactory.Opaque(rd)
        ??? // later
    }

    loop(r.op)
  }

  def mkAllRange(shape: Seq[Int]): Vec[Range] = shape.map(0 until _)(breakOut)

  //  @tailrec private def mkReaderFactory[S <: Sys[S]](m: Matrix[S], streamDim: Int)
  //                                                   (implicit tx: S#Tx /* , resolver: DataSource.Resolver[S] */): Matrix.Key =
  //    m match {
  //      case Matrix.Var(in1) =>
  //        mkReaderFactory(in1, streamDim)
  //      case dv: DataSource.Variable[S] =>
  //        val f     = dv.source.file
  //        val name  = dv.name
  //        new ReaderFactory.Transparent(file = f, name = name, streamDim = streamDim, section = mkAllRange(dv.shape))
  //      case r: Reduce[S] => mkReduceReaderFactory(r, streamDim)
  //      case _ => // "opaque"
  //        val source = m.getKey(streamDim)
  //        // new ReaderFactory.Opaque(source) // m.reader(streamDim))
  //        source
  //    }

  // Note: will throw exception if range is empty or going backwards
  private def toUcarRange(in: Range): ma2.Range = {
    // val inc = if (in.isInclusive) in else new Range.Inclusive(in.start, in.last, in.step)
    new ma2.Range(in.start, in.last, in.step)
  }

  private def toUcarSection(in: Vec[Range]): ma2.Section = {
    val sz      = in.size
    val list    = new ju.ArrayList[ma2.Range](sz)
    var i = 0; while (i < sz) {
      list.add(toUcarRange(in(i)))
      i += 1
    }
    new ma2.Section(list)
  }

  private sealed trait IndexMap {
    def next(ma: ma2.IndexIterator): Float
  }

  private object ByteIndexMap extends IndexMap {
    def next(ma: ma2.IndexIterator): Float = ma.getByteNext().toFloat
  }

  private object ShortIndexMap extends IndexMap {
    def next(ma: ma2.IndexIterator): Float = ma.getShortNext().toFloat
  }

  private object IntIndexMap extends IndexMap {
    def next(ma: ma2.IndexIterator): Float = ma.getIntNext().toFloat
  }

  private object LongIndexMap extends IndexMap {
    def next(ma: ma2.IndexIterator): Float = ma.getLongNext().toFloat
  }

  private object FloatIndexMap extends IndexMap {
    def next(ma: ma2.IndexIterator): Float = ma.getFloatNext()
  }

  private object DoubleIndexMap extends IndexMap {
    def next(ma: ma2.IndexIterator): Float = ma.getDoubleNext().toFloat
  }

  final class TransparentReader(v: nc2.Variable, streamDim: Int, section: Vec[Range])
    extends Reader {

    private val numFramesI = if (streamDim < 0) 1 else section(streamDim).size

    val numChannels: Int  = {
      val size          = (1L /: section)((prod, r) => prod * r.size)
      val numChannelsL  = size / numFramesI
      if (numChannelsL > 0xFFFF)
        throw new UnsupportedOperationException(s"The number of channels ($numChannelsL) is larger than supported")
      numChannelsL.toInt
    }

    private var pos = 0

    // NetcdfFile is not thread-safe
    private val sync = v.getParentGroup.getNetcdfFile

    // `isNumeric` is guaranteed. The types are: BYTE, FLOAT, DOUBLE, INT, SHORT, LONG

    private val indexMap = v.getDataType match {
      case ma2.DataType.FLOAT   => FloatIndexMap
      case ma2.DataType.DOUBLE  => DoubleIndexMap
      case ma2.DataType.INT     => IntIndexMap
      case ma2.DataType.LONG    => LongIndexMap
      case ma2.DataType.BYTE    => ByteIndexMap
      case ma2.DataType.SHORT   => ShortIndexMap
      case other                => throw new UnsupportedOperationException(s"Unsupported variable data type $other")
    }

    def numFrames = numFramesI.toLong

    def read(fBuf: Array[Array[Float]], off: Int, len: Int): Unit = {
      if (len < 0) throw new IllegalArgumentException(s"Illegal read length $len")
      val stop = pos + len
      if (stop > numFramesI) throw new EOFException(s"Reading past the end ($stop > $numFramesI)")
      val sect1 = if (pos == 0 && stop == numFramesI) section else {
        val newRange = sampleRange(section(streamDim), pos until stop)
        section.updated(streamDim, newRange)
      }
      val arr = sync.synchronized(v.read(toUcarSection(sect1)))
      // cf. Arrays.txt for (de-)interleaving scheme
      val t   = if (streamDim <= 0) arr else arr.transpose(0, streamDim)
      val it  = t.getIndexIterator

      var i = off
      val j = off + len
      while (i < j) {
        var ch = 0
        while (ch < numChannels) {
          fBuf(ch)(i) = indexMap.next(it)
          ch += 1
        }
        i += 1
      }

      pos = stop
    }
  }

  private val rangeVecSer = ImmutableSerializer.indexedSeq[Range](Serializers.RangeSerializer)

  private[matrix] def readIdentifiedKey(in: DataInput): Matrix.Key = {
    val tpeID     = in.readShort()
    (tpeID: @switch) match {
      case ReaderFactory.TransparentType =>
        val f         = file(in.readUTF())
        val name      = in.readUTF()
        val streamDim = in.readShort()
        val section   = rangeVecSer.read(in)
        new ReaderFactory.Transparent(file = f, name = name, streamDim = streamDim, section = section)

      case ReaderFactory.CloudyType =>
        val source    = Matrix.Key.read(in)
        val streamDim = in.readShort()
        val section   = rangeVecSer.read(in)
        new ReaderFactory.Cloudy(source = source, streamDim = streamDim, section = section)

      case _ => sys.error(s"Unexpected reduce key op $tpeID")
    }
  }

  object ReaderFactory {
    final val TransparentType = 0
    final val CloudyType      = 1

    sealed trait HasSection extends ReaderFactory {
      var section: Vec[Range]
    }

    final class Transparent(file: File, name: String, streamDim: Int, var section: Vec[Range])
      extends HasSection {

      protected def tpeID: Int = TransparentType

      def reader[S <: Sys[S]]()(implicit tx: S#Tx, resolver: DataSource.Resolver[S]): Reader = {
        val net = resolver.resolve(file)
        import JavaConversions._
        val v = net.getVariables.find(_.getShortName == name).getOrElse(
          sys.error(s"Variable '$name' does not exist in data source ${file.base}")
        )

        new TransparentReader(v, streamDim, section)
      }

      protected def writeFactoryData(out: DataOutput): Unit = {
        out.writeUTF(file.getPath)
        out.writeUTF(name)
        out.writeShort(streamDim)
        rangeVecSer.write(section, out)
      }
    }

    final class Cloudy(source: Matrix.Key, val streamDim: Int, var section: Vec[Range])
      extends HasSection {

      protected def tpeID: Int = CloudyType

      def reader[S <: Sys[S]]()(implicit tx: S#Tx, resolver: Resolver[S]): Reader = ??? // later

      protected def writeFactoryData(out: DataOutput): Unit = {
        source.write(out)
        out.writeShort(streamDim)
        rangeVecSer.write(section, out)
      }
    }

    //    /** Takes an eagerly instantiated reader, no possibility to optimize. */
    //    final class Opaque(val source: Matrix.Key) extends ReaderFactory {
    //      // def make()(implicit tx: S#Tx, resolver: DataSource.Resolver[S]): Reader = source
    //
    //      def tpeID: Int = ...
    //
    //      def reader[S <: Sys[S]]()(implicit tx: S#Tx, resolver: Resolver[S]): Reader = source.reader()
    //
    //      protected def writeFactoryData(out: DataOutput): Unit = ...
    //    }
  }
  sealed trait ReaderFactory extends impl.KeyImpl {
    protected def opID : Int = Reduce.opID
    protected def tpeID: Int

    final protected def writeData(out: DataOutput): Unit = {
      out.writeShort(opID)
      writeFactoryData(out)
    }

    protected def writeFactoryData(out: DataOutput): Unit
  }

  //  private final class KeyImpl[S](reduce: Reduce[S], val streamDim: Int) extends impl.KeyImpl[S] {
  //    protected def opID: Int = Reduce.opID
  //
  //    def reader()(implicit resolver: Resolver[S]): Reader = {
  //      val rf = mkReduceReaderFactory(reduce, streamDim)
  //      rf.make()
  //    }
  //
  //    protected def writeData(out: DataOutput): Unit = ...
  //  }

  private final class Impl[S <: Sys[S]](protected val targets: evt.Targets[S], val in: Matrix[S],
                                        val dim: Selection[S], val op: Op[S])
    extends Reduce[S]
    with MatrixProxy[S]
    with evt.impl.StandaloneLike[S, Matrix.Update[S], Matrix[S]] {

    override def toString() = s"Reduce$id($in, $dim, $op)"

    protected def matrixPeer(implicit tx: S#Tx): Matrix[S] = in

    //    def reader(streamDim: Int)(implicit tx: S#Tx, resolver: Resolver[S]): Reader = {
    //      val rf = mkReduceReaderFactory(this, streamDim)
    //      rf.make()
    //    }

    def getKey(streamDim: Int)(implicit tx: S#Tx): Matrix.Key = mkReduceReaderFactory(this, streamDim)

    override def debugFlatten(implicit tx: S#Tx): Vec[Double] = {
      implicit val resolver = DataSource.Resolver.empty[S]
      val r   = reader(-1)
      val buf = Array.ofDim[Float](r.numChannels, r.numFrames.toInt)
      r.read(buf, 0, 1)
      val res = Vec.tabulate(r.numChannels)(ch => buf(ch)(0).toDouble)
      return res

      val data  = in.debugFlatten
      val idx   = indexOfDim
      if (idx == -1) return data

      // currently support only `Apply` and `Slice`.
      // Flat indices work as follows: dimensions are flatten from inside to outside,
      // so the last dimension uses consecutive samples.
      // (d0_0, d1_0, d2_0), (d0_0, d1_0, d2_1), ... (d0_0, d1_0, d2_i),
      // (d0_0, d1_1, d2_0), (d0_0, d1_1, d2_1), ... (d0_0, d1_1, d2_i),
      // ...
      // (d0_0, d1_j, d2_0), (d0_0, d1_j, d2_1), ... (d0_0, d1_j, d2_i),
      // (d0_1, d1_0, d2_0), (d0_0, d1_0, d2_1), ... (d0_0, d1_0, d2_i),
      // ... ...
      // ... ... (d0_k, d1_j, d2_i)

      // therefore, if the selected dimension index is 0 <= si < rank,
      // and the operator's start index is `lo` and the stop index is `hi` (exclusive),
      // the copy operations is as follows:

      // val num    = shape.take(si    ).product  // d0: 1, d1: k, d2: k * j
      // val stride = shape.drop(si    ).product  // d0: k * j * i, d1: j * i, d2: i
      // val block  = shape.drop(si + 1).product  // d0: j * i, d1: i, d2: 1
      // for (x <- 0 until num) {
      //   val offset = x * stride
      //   copy `lo * block + offset` until `hi * block + offset`
      // }

      val (lo, hi): (Int, Int) = throw new Exception() // rangeOfDim(idx)
      val sz = hi - lo + 1
      // if (sz <= 0) return Vec.empty  // or throw exception?

      val sh      = in.shape
      val num     = sh.take(idx    ).product
      val block   = sh.drop(idx + 1).product
      val stride  = block * sh(idx)
      val szFull  = num * stride        // full size
      val szRed   = num * block * sz    // reduced size

      val b     = Vec.newBuilder[Double]
      b.sizeHint(szRed)
      for (x <- 0 until szFull by stride) {
        for (y <- lo * block + x until (hi+1) * block + x) {
          b += data(y)
        }
      }

      b.result()
    }

    override def shape(implicit tx: S#Tx): Vec[Int] = {
      val sh        = in.shape
      val idx       = indexOfDim
      if (idx == -1) return sh

      val szIn      = sh(idx)
      val sz        = op.size(szIn) // val (idx, sz) = indexAndSize
      if (sz <= 0) Vec.empty  // or throw exception?
      else sh.updated(idx, sz)
    }

    private def validateIndex(idx: Int)(implicit tx: S#Tx): Int =
      if (idx >= 0 && idx < in.rank) idx else -1

    def indexOfDim(implicit tx: S#Tx): Int = {
      @tailrec def loop(sel: Selection[S])(implicit tx: S#Tx): Int = sel match {
        case si: Selection.Index[S] => si.expr.value
        case sn: Selection.Name [S] => in.dimensions.indexWhere(_.name == sn.expr.value)
        case sv: Selection.Var  [S] => loop(sv())
      }
      validateIndex(loop(dim))
    }

//    private def rangeOfDim(idx: Int)(implicit tx: S#Tx): (Int, Int) = {
//      @tailrec def loop(_op: Op[S]): (Int, Int) = _op match {
//        case oa: Op.Apply[S] =>
//          val _lo  = oa.index.value
//          val _hi  = _lo // + 1
//          (_lo, _hi)
//
//        case os: Op.Slice[S] =>
//          val _lo = os.from .value
//          val _hi = os.to   .value
//          (_lo, _hi)
//
//        case os: Op.Stride[S] => ...
//
//        case ov: Op.Var  [S] => loop(ov())
//      }
//
//      val (lo, hi) = loop(op)
//      (math.max(0, lo), math.min(in.shape.apply(idx) - 1, hi))
//    }

//    private def indexAndSize(implicit tx: S#Tx): (Int, Int) = {
//      val idx = indexOfDim
//      if (idx == -1) return (-1, -1)   // or throw exception?
//
//      val (lo, hi) = rangeOfDim(idx)
//      val sz = hi - lo + 1
//      (idx, sz)
//    }

    protected def writeData(out: DataOutput): Unit = {
      out writeByte 1   // cookie
      out writeInt Matrix.typeID
      out writeInt Reduce.opID
      in  write out
      dim write out
      op  write out
    }

    protected def disposeData()(implicit tx: S#Tx) = ()

    // ---- event ----

    def changed: EventLike[S, Matrix.Update[S]] = this

    protected def reader: evt.Reader[S, Matrix[S]] = Matrix.serializer

    def connect()(implicit tx: S#Tx): Unit = {
      in .changed ---> this
      dim.changed ---> this
      op .changed ---> this
    }

    def disconnect()(implicit tx: S#Tx): Unit = {
      in .changed -/-> this
      dim.changed -/-> this
      op .changed -/-> this
    }

    def pullUpdate(pull: evt.Pull[S])(implicit tx: S#Tx): Option[Matrix.Update[S]] = {
      val e0 =       pull.contains(in .changed) && pull(in .changed).isDefined
      val e1 = e0 || pull.contains(dim.changed) && pull(dim.changed).isDefined
      val e2 = e1 || pull.contains(op .changed) && pull(op .changed).isDefined
      if (e2) Some(Matrix.Update.Generic(this)) else None
    }
  }
}