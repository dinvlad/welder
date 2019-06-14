package org.broadinstitute.dsp.workbench.welder
package server

import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.util.UUID.randomUUID
import java.util.concurrent.TimeUnit

import _root_.fs2.{Stream, io}
import _root_.io.chrisdavenport.log4cats.Logger
import _root_.io.circe.{Decoder, Encoder}
import _root_.io.circe.syntax._
import ca.mrvisser.sealerate
import cats.data.Kleisli
import cats.effect.{ContextShift, IO, Timer}
import cats.implicits._
import org.broadinstitute.dsde.workbench.google2
import org.broadinstitute.dsde.workbench.google2.{GcsBlobName, GoogleStorageService}
import org.broadinstitute.dsde.workbench.model.google.GcsBucketName
import org.broadinstitute.dsde.workbench.model.{TraceId, WorkbenchEmail}
import org.broadinstitute.dsp.workbench.welder.JsonCodec._
import org.broadinstitute.dsp.workbench.welder.SourceUri.{DataUri, GsPath}
import org.broadinstitute.dsp.workbench.welder.server.ObjectService._
import org.broadinstitute.dsp.workbench.welder.server.PostObjectRequest._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.Http4sDsl

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

class ObjectService(
    config: ObjectServiceConfig,
    googleStorageService: GoogleStorageService[IO],
    blockingEc: ExecutionContext,
    storageLinksCache: StorageLinksCache,
    metadataCache: MetadataCache
)(implicit cs: ContextShift[IO], logger: Logger[IO], timer: Timer[IO])
    extends Http4sDsl[IO] {
  val service: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "metadata" =>
      for {
        traceId <- IO(TraceId(randomUUID()))
        metadataReq <- req.as[GetMetadataRequest]
        res <- checkMetadata(metadataReq).run(traceId)
        resp <- Ok(res)
      } yield resp
    case req @ POST -> Root =>
      for {
        traceId <- IO(TraceId(randomUUID()))
        localizeReq <- req.as[PostObjectRequest]
        res <- localizeReq match {
          case x: Localize => localize(x) >> NoContent()
          case x: SafeDelocalize => safeDelocalize(x).run(traceId) >> NoContent()
          case x: Delete => delete(x).run(traceId) >> NoContent()
        }
      } yield res
  }

  def localize(req: Localize): IO[Unit] = {
    val res = Stream
      .emits(req.entries)
      .map { entry =>
        val localAbsolutePath = config.workingDirectory.resolve(entry.localObjectPath)

        val localizeFile = entry.sourceUri match {
          case DataUri(data) => Stream.emits(data).through(io.file.writeAll[IO](localAbsolutePath, blockingEc))
          case GsPath(bucketName, blobName) =>
            for {
              traceId <- Stream.emit(TraceId(randomUUID())).covary[IO]
              _ <- gcsToLocalFile(localAbsolutePath, bucketName, blobName)
              meta <- Stream.eval(retrieveGcsMetadata(entry.localObjectPath, bucketName, blobName, traceId))
              _ <- meta match {
                    case Some(m) =>
                      Stream.eval_(metadataCache.modify(mp => (mp + (entry.localObjectPath -> m), ())))
                    case _ => Stream.eval(IO.unit)
                  }
            } yield ()
        }

        Stream.eval(mkdirIfNotExist(localAbsolutePath.getParent)) >> localizeFile
      }
      .parJoin(10)

    res.compile.drain
  }

  private def retrieveGcsMetadata(localPath: java.nio.file.Path, bucketName: GcsBucketName, blobName: GcsBlobName, traceId: TraceId): IO[Option[GcsMetadata]] = for {
    meta <- googleStorageService.getObjectMetadata(bucketName, blobName, Some(traceId)).compile.last
    res <- meta match {
      case Some(google2.GetMetadataResponse.Metadata(crc32c, userDefinedMetadata, generation)) =>
        for {
          lastLockedBy <- IO.pure(userDefinedMetadata.get("lastLockedBy").map(WorkbenchEmail))
          expiresAt <- userDefinedMetadata.get("lockExpiresAt").flatTraverse {
            expiresAtString =>
              for {
                ea <- Either.catchNonFatal(expiresAtString.toLong).fold[IO[Option[Long]]](_ => logger.warn(s"Failed to convert $expiresAtString to epoch millis") *> IO.pure(None), l => IO.pure(Some(l)))
                instant = ea.map(Instant.ofEpochMilli)
              } yield instant
          }
        } yield Some(GcsMetadata(localPath, lastLockedBy, expiresAt, crc32c, generation))
      case _ => IO.pure(None)
    }
  } yield res

  def checkMetadata(req: GetMetadataRequest): Kleisli[IO, TraceId, MetadataResponse] = {
    findStorageLink(
      req.localObjectPath,
      pair => Kleisli.liftF(IO.pure(MetadataResponse.SafeMode(pair.storageLink))),
      pair => Kleisli { traceId =>
        val fullBlobName = getFullBlobName(pair.basePath, req.localObjectPath, pair.storageLink.cloudStorageDirectory.blobPath)
        for {
          metadata <- retrieveGcsMetadata(req.localObjectPath, pair.storageLink.cloudStorageDirectory.bucketName, fullBlobName, traceId)
          result <- metadata match {
            case None =>
              IO(MetadataResponse.RemoteNotFound(pair.storageLink))
            case Some(GcsMetadata(_, lastLockedBy, expiresAt, crc32c, generation)) =>
              val localAbsolutePath = config.workingDirectory.resolve(req.localObjectPath)
              for {
                fileBody <- io.file.readAll[IO](localAbsolutePath, blockingEc, 4096).compile.to[Array]
                calculatedCrc32c = Crc32c.calculateCrc32c(fileBody)
                syncStatus <- if (calculatedCrc32c == crc32c) IO.pure(SyncStatus.Live) else {
                  for {
                    cachedGeneration <- metadataCache.get.map(_.get(req.localObjectPath))
                    status <- cachedGeneration match {
                      case Some(cachedGen) => if(cachedGen.generation == generation) IO.pure(SyncStatus.LocalChanged) else IO.pure(SyncStatus.RemoteChanged)
                      case None => IO.pure(SyncStatus.Desynchronized)
                    }
                  } yield status
                }
                currentTime <- timer.clock.realTime(TimeUnit.MILLISECONDS)
                lastLock <- expiresAt.flatTraverse[IO, WorkbenchEmail] { ea =>
                  if (currentTime > ea.toEpochMilli)
                    IO.pure(none[WorkbenchEmail])
                  else
                    IO.pure(lastLockedBy)
                }
              } yield MetadataResponse.EditMode(syncStatus, lastLock, generation, pair.storageLink)
          }
        } yield result
      }
    )
  }

  def safeDelocalize(req: SafeDelocalize): Kleisli[IO, TraceId, Unit] = {
    findStorageLink[Unit](
      req.localObjectPath,
      _ => Kleisli.liftF(IO.raiseError(SafeDelocalizeSafeModeFileError(s"${req.localObjectPath} can't be delocalized since it's in safe mode"))),
      pair => Kleisli { traceId =>
        val localAbsolutePath = config.workingDirectory.resolve(req.localObjectPath)
        syncRemote(
          pair.basePath,
          req.localObjectPath,
          pair.storageLink.cloudStorageDirectory,
          (gsPath, metadata) => delocalize(localAbsolutePath, gsPath, traceId, metadata.generation), //TODO: update metadata generation cache once https://github.com/broadinstitute/workbench-libs/pull/243 is merged
          gsPath => delocalize(localAbsolutePath, gsPath, traceId, 0L)
        )
      }
    )
  }

  def delete(req: Delete): Kleisli[IO, TraceId, Unit] = {
    findStorageLink[Unit](
      req.localObjectPath,
      _ => Kleisli.liftF(IO.raiseError(DeleteSafeModeFileError(s"${req.localObjectPath} can't be deleted since it's in safe mode"))),
      pair => Kleisli { traceId =>
        syncRemote(
          pair.basePath,
          req.localObjectPath,
          pair.storageLink.cloudStorageDirectory,
          (gsPath, metadata) =>
            googleStorageService.removeObject(gsPath.bucketName, gsPath.blobName, Some(traceId)).void, //delete file from gcs with generation once https://github.com/broadinstitute/workbench-libs/pull/242 is approved
          _ => IO.raiseError(UnknownFileState(s"Local GCS metadata for ${req.localObjectPath} not found"))
        )
      }
    )
  }

  private def mkdirIfNotExist(path: java.nio.file.Path): IO[Unit] = {println(s"path: $path")
    val directory = new File(path.toString)
    if (!directory.exists) {
      IO(directory.mkdirs).void
    }  else IO.unit
  }

  private def gcsToLocalFile(localAbsolutePath: java.nio.file.Path, gcsBucketName: GcsBucketName, gcsBlobName: GcsBlobName): Stream[IO, Unit] = {
    googleStorageService.getObject(gcsBucketName, gcsBlobName, None) //get file from google.
      .through(io.file.writeAll(localAbsolutePath, blockingEc))
  }

  private def syncRemote[A](
                          basePath: java.nio.file.Path,
                          localPath: java.nio.file.Path,
                          cloudStorageDirectory: CloudStorageDirectory,
                          whenMetadataFound: (GsPath, GcsMetadata) => IO[A],
                          whenMetadataNotFound: (GsPath) => IO[A]
                         ): IO[A] =
      for {
        previousMeta <- metadataCache.get.map(_.get(localPath))
        fullBlobName = getFullBlobName(basePath, localPath, cloudStorageDirectory.blobPath)
        gsPath = GsPath(cloudStorageDirectory.bucketName, fullBlobName)
        res <- previousMeta match {
          case Some(m) => whenMetadataFound(gsPath, m)
          case None => whenMetadataNotFound(gsPath) //
        }
      } yield res

  private def delocalize(localAbsolutePath: java.nio.file.Path, gsPath: GsPath, traceId: TraceId, generation: Long): IO[Unit] =
    io.file.readAll[IO](localAbsolutePath, blockingEc, 4096).compile.to[Array].flatMap { body =>
      googleStorageService
          .storeObject(gsPath.bucketName, gsPath.blobName, body, gcpObjectType, Map.empty, Some(generation), Some(traceId))
          .compile
          .drain
          .adaptError {
            case e: com.google.cloud.storage.StorageException if e.getCode == 412 =>
              GenerationMismatch(s"Remote version has changed for ${localAbsolutePath}. Generation mismatch")
          }
    }

  private def findStorageLink[A](localPath: java.nio.file.Path, safeModeResponse: BasePathAndStorageLink => Kleisli[IO, TraceId, A], editModeResponse: BasePathAndStorageLink => Kleisli[IO, TraceId, A]): Kleisli[IO, TraceId, A] = for {
    storageLinks <- Kleisli.liftF(storageLinksCache.get)
    baseDirectories <- Kleisli.liftF(IO.pure(getPosssibleBaseDirectory(localPath)))
    basePath = baseDirectories.collectFirst {
      case x if (storageLinks.get(x).isDefined) =>
        val sl = storageLinks.get(x).get
        val basePathAndStorageLink = BasePathAndStorageLink(x, sl)
        if (sl.localSafeModeBaseDirectory.path == x)
          safeModeResponse(basePathAndStorageLink)
        else
          editModeResponse(basePathAndStorageLink)
    }
    res <- basePath.fold[Kleisli[IO, TraceId, A]](Kleisli.liftF(IO.raiseError(StorageLinkNotFoundException(s"No storage link found for ${localPath}"))))(identity)
  } yield res
}

object ObjectService {
  def apply(config: ObjectServiceConfig, googleStorageService: GoogleStorageService[IO], blockingEc: ExecutionContext, storageLinksCache: StorageLinksCache, metadataCache: MetadataCache)(
      implicit cs: ContextShift[IO],
      logger: Logger[IO],
      timer: Timer[IO]
  ): ObjectService = new ObjectService(config, googleStorageService, blockingEc, storageLinksCache, metadataCache)

  implicit val actionDecoder: Decoder[Action] = Decoder.decodeString.emap { str =>
    Action.stringToAction.get(str).toRight("invalid action")
  }

  implicit val entryDecoder: Decoder[Entry] = Decoder.instance { cursor =>
    for {
      bucketAndObject <- cursor.downField("sourceUri").as[SourceUri]
      localObjectPath <- cursor.downField("localDestinationPath").as[Path]
    } yield Entry(bucketAndObject, localObjectPath)
  }

  implicit val localizeDecoder: Decoder[Localize] = Decoder.forProduct1("entries") {
    Localize.apply
  }

  implicit val safeDelocalizeDecoder: Decoder[SafeDelocalize] = Decoder.forProduct1("localPath") {
    SafeDelocalize.apply
  }

  implicit val deleteDelocalizeDecoder: Decoder[Delete] = Decoder.forProduct1("localPath") {
    Delete.apply
  }

  implicit val postObjectRequestDecoder: Decoder[PostObjectRequest] = Decoder.instance { cursor =>
    for {
      action <- cursor.downField("action").as[Action]
      req <- action match {
        case Action.Localize =>
          cursor.as[Localize]
        case Action.SafeDelocalize =>
          cursor.as[SafeDelocalize]
        case Action.Delete =>
          cursor.as[Delete]
      }
    } yield req
  }

  implicit val getMetadataDecoder: Decoder[GetMetadataRequest] = Decoder.forProduct1("localPath")(GetMetadataRequest.apply)

  implicit val metadataResponseEditModeEncoder: Encoder[MetadataResponse.EditMode] = Encoder.forProduct4(
    "syncMode",
    "syncStatus",
    "lastLockedBy",
    "storageLink"
  )(x => (x.syncMode, x.syncStatus, x.lastLockedBy, x.storageLink))

  implicit val metadataResponseRemoteNotFoundEncoder: Encoder[MetadataResponse.RemoteNotFound] = Encoder.forProduct3(
    "syncMode",
    "syncStatus",
    "storageLink"
  )(x => (x.syncMode, x.syncStatus, x.storageLink))

  implicit val metadataResponseSafeModeEncoder: Encoder[MetadataResponse.SafeMode] = Encoder.forProduct2(
    "syncMode",
    "storageLink"
  )(x => (x.syncMode, x.storageLink))

  implicit val metadataResponseEncoder: Encoder[MetadataResponse] = Encoder.instance { x =>
    x match {
      case x: MetadataResponse.SafeMode => x.asJson
      case x: MetadataResponse.EditMode => x.asJson
      case x: MetadataResponse.RemoteNotFound => x.asJson
    }
  }
}

final case class GetMetadataRequest(localObjectPath: Path)
sealed abstract class Action
object Action {
  final case object Localize extends Action {
    override def toString: String = "localize"
  }
  final case object SafeDelocalize extends Action {
    override def toString: String = "safeDelocalize"
  }
  final case object Delete extends Action {
    override def toString: String = "delete"
  }

  val stringToAction: Map[String, Action] = sealerate.values[Action].map(a => a.toString -> a).toMap
}
sealed abstract class PostObjectRequest extends Product with Serializable {
  def action: Action
}
object PostObjectRequest {
  final case class Localize(entries: List[Entry]) extends PostObjectRequest {
    override def action: Action = Action.Localize
  }
  final case class SafeDelocalize(localObjectPath: Path) extends PostObjectRequest {
    override def action: Action = Action.SafeDelocalize
  }
  final case class Delete(localObjectPath: Path) extends PostObjectRequest {
    override def action: Action = Action.Delete
  }
}

sealed abstract class MetadataResponse extends Product with Serializable {
  def syncMode: SyncMode
}
object MetadataResponse {
  case class SafeMode(storageLink: StorageLink) extends MetadataResponse {
    def syncMode: SyncMode = SyncMode.Safe
  }

  final case class EditMode(syncStatus: SyncStatus, lastLockedBy: Option[WorkbenchEmail], generation: Long, storageLink: StorageLink)
      extends MetadataResponse {
    def syncMode: SyncMode = SyncMode.Edit
  }

  final case class RemoteNotFound(storageLink: StorageLink) extends MetadataResponse {
    def syncMode: SyncMode = SyncMode.Edit
    def syncStatus: SyncStatus = SyncStatus.RemoteNotFound
  }
}

final case class Entry(sourceUri: SourceUri, localObjectPath: Path)

final case class LocalizeRequest(entries: List[Entry])

final case class ObjectServiceConfig(workingDirectory: Path, ownerEmail: WorkbenchEmail, lockExpiration: FiniteDuration)

final case class BasePathAndStorageLink(basePath: Path, storageLink: StorageLink)