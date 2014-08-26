/*
 *  DataSource.scala
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

package de.sciss.lucre.matrix

import ucar.nc2
import java.io.File
import impl.{DataSourceImpl => Impl}
import de.sciss.serial.{DataInput, Writable, Serializer}
import de.sciss.lucre.stm.Mutable
import scala.collection.breakOut

object DataSource {
  /** Creates a new data source from a given path that points to a NetCDF file. This
    * will open the file and build a model of its variables.
    *
    * @param file       file reference pointing to a NetCDF file
    * @param resolver   the resolver is used to actually open (a possibly cached) NetCDF file
    */
  def apply[S <: Sys[S]](file: File)(implicit tx: S#Tx, resolver: Resolver[S]): DataSource[S] = Impl(file)

  implicit def serializer[S <: Sys[S]]: Serializer[S#Tx, S#Acc, DataSource[S]] =
    Impl.serializer[S]

  def read[S <: Sys[S]](in: DataInput, access: S#Acc)(implicit tx: S#Tx): DataSource[S] = Impl.read(in, access)

  object Variable {
    final val opID = 3

    implicit def serializer[S <: Sys[S]]: Serializer[S#Tx, S#Acc, Variable[S]] = Impl.varSerializer

    //    def apply[S <: Sys[S]](source: DataSource[S], parents: List[String], name: String)
    //                          (implicit tx: S#Tx): Variable[S] =
    //      Impl.variable(source, parents, name)

    def read[S <: Sys[S]](in: DataInput, access: S#Acc)(implicit tx: S#Tx): Variable[S] = Impl.readVariable(in, access)
  }
  trait Variable[S <: Sys[S]] extends Matrix[S] with Writable {
    def source /* (implicit tx: S#Tx) */: DataSource[S]
    def parents: List[String]

    //    def name: String
    //
    //    def shape: Vec[(String, Range)]
    //
    //    def rank: Int
    //
    //    def size: Long

    def data()(implicit tx: S#Tx, resolver: Resolver[S]): nc2.Variable
  }

  object Resolver {
    def seq[S <: Sys[S]](files: nc2.NetcdfFile*): Resolver[S] = new Seq(files.map(net => (net.getLocation, net))(breakOut))

    def empty[S <: Sys[S]]: Resolver[S] = new Seq(Map.empty)

    private final class Seq[S <: Sys[S]](map: Map[String, nc2.NetcdfFile]) extends Resolver[S] {
      def resolve(file: File)(implicit tx: S#Tx): nc2.NetcdfFile = {
        val p = file.getPath
        map.getOrElse(file.getPath, throw new NoSuchElementException(p))
      }
    }
  }
  trait Resolver[S <: Sys[S]] {
    def resolve(file: File)(implicit tx: S#Tx): nc2.NetcdfFile
  }
}
/** A document represents one open data file. */
trait DataSource[S <: Sys[S]] extends Mutable[S#ID, S#Tx] {
  /** Path to the document's underlying file (NetCDF). */
  def path: String

  def file: File

  def data()(implicit tx: S#Tx, resolver: DataSource.Resolver[S]): nc2.NetcdfFile

  def variables(implicit tx: S#Tx): List[DataSource.Variable[S]]
}