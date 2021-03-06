package llsm.interpreters

import java.nio.file.Files
import scala.collection.JavaConverters._

import cats._
import cats.free.Free
import cats.implicits._
import cats.arrow.FunctionK
import cats.data.Kleisli
import cats.free.FreeApplicative
import llsm.algebras.{
  LoggingAPI,
  LoggingF,
  MetadataAPI,
  MetadataF,
  MetadataLow,
  MetadataLowF
 }
import llsm.fp._
import llsm.io.metadata.{
  ConfigurableMetadata,
  FilenameMetadata,
  FileMetadata,
  Parser,
  ParsingFailure
 }
import llsm.io.metadata.implicits._
import org.scijava.Context

trait MetadataInterpreters {

  type Exec[M[_], A] = Kleisli[M, Unit, A]

  val metaFToMetaLowF: MetadataF ≈< MetadataLowF =
    new (MetadataF ≈< MetadataLowF) {
      def apply[A](fa: MetadataF[A]): FreeApplicative[MetadataLowF, A] =
        fa match {
          case MetadataAPI.ReadMetadata(path, next) =>
            (MetadataLow.extractFilenameMeta(path) |@|
             MetadataLow.extractTextMeta(path) |@|
             MetadataLow.extractImgMeta(path) |@|
             MetadataLow.configurableMeta).map {
              case (f, t, i, c) =>
                next(FileMetadata(f, t.waveform, t.camera, t.sample, c, i))
            }
              case MetadataAPI.WriteMetadata(path, meta, next) =>
                MetadataLow.writeMeta(path, meta).map(next)
        }
    }


  private def simpleMetaLowReader[M[_]](
    config: ConfigurableMetadata,
    context: Context
  )(
    implicit
    M: ApplicativeError[M, Throwable]
  ) = λ[FunctionK[MetadataLowF, Exec[M, ?]]] {
    fa => Kleisli { _ =>
      fa match {
        case MetadataLow.ConfigurableMeta => M.pure(config)
        case MetadataLow.ExtractFilenameMeta(path) =>
          M.catchNonFatal(
            Parser[FilenameMetadata](path.getFileName.toString) match {
              case Right(fm) => fm
              case Left(ParsingFailure(_, e)) => throw new Exception(e)
            }
          )
        case MetadataLow.ExtractTextMeta(path) => M.catchNonFatal {
          val parent = path.getParent
          Files.list(parent).iterator.asScala
            .find(_.toString.endsWith(".txt")) match {
              case Some(p) => FileMetadata.readMetadataFromTxtFile(p) match {
                case Right(m) => m
                case Left(FileMetadata.MetadataIOError(msg)) => throw new Exception(msg)
              }
              case None => throw new Exception("FileMetadata file not found")
            }
        }
        case MetadataLow.ExtractBasicImageMeta(path) => M.pure {
          // val scifio = new SCIFIO(context)
          // val format = scifio.format().getFormat(path.toString)
          // val meta = format.createParser().parse(path.toString).get(0)
          None
        }
        case MetadataLow.WriteMetadata(path, metas) =>
          M.catchNonFatal {
          }
      }
    }
  }


  def basicMetadataReader[M[_]](
    config: ConfigurableMetadata,
    context: Context
  )(
    implicit
    M: ApplicativeError[M, Throwable]
  ): MetadataF ~> M =
    new (MetadataF ~> M) {
      def apply[A](fa: MetadataF[A]): M[A] =
        metaFToMetaLowF(fa).foldMap(simpleMetaLowReader[M](config, context)).run(())
  }

  val metadataLogging: MetadataF ~< Halt[LoggingF, ?] =
    new (MetadataF ~< Halt[LoggingF, ?]) {
      def apply[A](fa: MetadataF[A]): Free[Halt[LoggingF, ?], A] =
        fa match {
          case MetadataAPI.ReadMetadata(path, _) =>
            Free.liftF[Halt[LoggingF, ?], A](LoggingAPI[LoggingF].debug(s"Reading metadata for: $path"))
          case MetadataAPI.WriteMetadata(path, _, _) =>
            Free.liftF[Halt[LoggingF, ?], A](LoggingAPI[LoggingF].debug(s"Reading metadata for: $path"))
        }
    }
}
