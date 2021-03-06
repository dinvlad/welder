package org.broadinstitute.dsp.workbench.welder

import java.nio.file.Path

import cats.effect.{Blocker, ContextShift, IO, Timer}
import cats.mtl.ApplicativeAsk
import fs2.{Pipe, Stream}
import io.chrisdavenport.log4cats.Logger
import io.circe.Decoder
import org.broadinstitute.dsde.workbench.google2.{Crc32, GcsBlobName, GoogleStorageService, RemoveObjectResult}
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.model.google.GcsBucketName
import org.broadinstitute.dsp.workbench.welder.SourceUri.GsPath

import scala.util.matching.Regex

trait GoogleStorageAlg {
  def updateMetadata(gsPath: GsPath, traceId: TraceId, metadata: Map[String, String]): IO[UpdateMetadataResponse]
  def retrieveAdaptedGcsMetadata(localPath: RelativePath, gsPath: GsPath, traceId: TraceId): IO[Option[AdaptedGcsMetadata]]
  def removeObject(gsPath: GsPath, traceId: TraceId, generation: Option[Long]): Stream[IO, RemoveObjectResult]

  /**
    * Overwrites the file if it already exists locally
    */
  def gcsToLocalFile(localAbsolutePath: java.nio.file.Path, gsPath: GsPath, traceId: TraceId): Stream[IO, AdaptedGcsMetadata]

  /**
    * Delocalize user's files to GCS.
    */
  def delocalize(
      localObjectPath: RelativePath,
      gsPath: GsPath,
      generation: Long,
      userDefinedMeta: Map[String, String],
      traceId: TraceId
  ): IO[DelocalizeResponse]

  /**
    * Copy file to GCS without checking generation, and adding user metadata
    */
  def fileToGcs(localObjectPath: RelativePath, gsPath: GsPath)(implicit ev: ApplicativeAsk[IO, TraceId]): IO[Unit]

  /**
    * Copy file to GCS without checking generation, and adding user metadata
    */
  def fileToGcsAbsolutePath(localFile: Path, gsPath: GsPath)(implicit ev: ApplicativeAsk[IO, TraceId]): IO[Unit]

  /**
    * Recursively download files in cloudStorageDirectory to local directory.
    * If file exists locally, we don't download the file
    * @param localBaseDirectory: base directory where remote files will be download to
    * @param cloudStorageDirectory: GCS directory where files will be download from
    * @return AdaptedGcsMetadataCache that should be added to local metadata cache
    */
  def localizeCloudDirectory(
      localBaseDirectory: RelativePath,
      cloudStorageDirectory: CloudStorageDirectory,
      workingDir: Path,
      pattern: Regex,
      traceId: TraceId
  ): Stream[IO, AdaptedGcsMetadataCache]

  def uploadBlob(bucketName: GcsBucketName, objectName: GcsBlobName): Pipe[IO, Byte, Unit]

  def getBlob[A: Decoder](bucketName: GcsBucketName, blobName: GcsBlobName): Stream[IO, A]
}

object GoogleStorageAlg {
  def fromGoogle(
      config: GoogleStorageAlgConfig,
      blocker: Blocker,
      googleStorageService: GoogleStorageService[IO]
  )(implicit logger: Logger[IO], timer: Timer[IO], cs: ContextShift[IO]): GoogleStorageAlg =
    new GoogleStorageInterp(config, blocker, googleStorageService)
}

final case class GoogleStorageAlgConfig(workingDirectory: Path)
final case class DelocalizeResponse(generation: Long, crc32c: Crc32)

sealed trait UpdateMetadataResponse extends Product with Serializable
object UpdateMetadataResponse {
  final case object DirectMetadataUpdate extends UpdateMetadataResponse
  final case class ReUploadObject(generation: Long, crc32c: Crc32) extends UpdateMetadataResponse
}
