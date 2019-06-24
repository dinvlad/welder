package org.broadinstitute.dsp.workbench.welder

import java.io.File
import java.nio.file.Paths
import java.util.UUID.randomUUID

import cats.effect.IO
import com.google.api.client.googleapis.json.GoogleJsonError
import com.google.cloud.storage.Blob
import fs2.Stream
import org.broadinstitute.dsde.workbench.google2.mock.{BaseFakeGoogleStorage, FakeGoogleStorageInterpreter}
import org.broadinstitute.dsde.workbench.google2.{GcsBlobName, GetMetadataResponse}
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.model.google.GcsBucketName
import org.broadinstitute.dsp.workbench.welder.Generators._
import org.broadinstitute.dsp.workbench.welder.SourceUri.GsPath
import org.scalatest.FlatSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.concurrent.ExecutionContext.global

class GoogleStorageInterpSpec extends FlatSpec with ScalaCheckPropertyChecks with WelderTestSuite {
  //if one day java emulator supports metadata, we shouldn't ignore this test
  ignore should "be able to set metadata when 403 happens" in {
    forAll {
      (gsPath: GsPath) =>
        val bodyBytes = "this is great!".getBytes("UTF-8")
        val googleStorage = new BaseFakeGoogleStorage {
          override def setObjectMetadata(bucketName: GcsBucketName,blobName: GcsBlobName,metadata: Map[String,String],traceId: Option[TraceId]): fs2.Stream[IO,Unit] = {
            val errors = new GoogleJsonError()
            errors.setCode(403)
            Stream.raiseError[IO](new com.google.cloud.storage.StorageException(errors))
          }
        }
        val googleStorageAlg = GoogleStorageAlg.fromGoogle(GoogleStorageAlgConfig(Paths.get("/tmp")), googleStorage)
        val res = for {
          _ <- googleStorage.createBlob(gsPath.bucketName, gsPath.blobName, bodyBytes, "text/plain", Map.empty, None, None).compile.drain
          _ <- googleStorageAlg.updateMetadata(gsPath, TraceId(randomUUID()), Map("lastLockedBy" -> "me"))
          meta <- googleStorage.getObjectMetadata(gsPath.bucketName, gsPath.blobName, None).compile.lastOrError
        } yield {
          meta.asInstanceOf[GetMetadataResponse.Metadata].userDefined.get("lastLockedBy") shouldBe("me")
        }
        res.unsafeRunSync()
    }
  }

  "delocalize" should "fail with GenerationMismatch exception if remote file has changed" in {
    forAll {
      (localObjectPath: RelativePath, gsPath: GsPath) =>
        val bodyBytes = "this is great!".getBytes("UTF-8")
        val googleStorage = GoogleStorageAlg.fromGoogle(GoogleStorageAlgConfig(Paths.get("/tmp")), GoogleStorageServiceWithFailures)
        val localAbsolutePath = Paths.get(s"/tmp/${localObjectPath.asPath.toString}")
        // Create the local base directory
        val directory = new File(s"${localAbsolutePath.getParent.toString}")
        if (!directory.exists) {
          directory.mkdirs
        }
        val res = for {
          _ <- Stream.emits(bodyBytes).covary[IO].through(fs2.io.file.writeAll[IO](localAbsolutePath, global)).compile.drain //write to local file
          resp <- googleStorage.delocalize(localObjectPath, gsPath, 0L, TraceId(randomUUID())).attempt
          _ <- IO((new File(localAbsolutePath.toString)).delete())
        } yield {
          resp shouldBe Left(GenerationMismatch(s"Remote version has changed for ${localAbsolutePath}. Generation mismatch"))
        }
        res.unsafeRunSync()
    }
  }

  "gcsToLocalFile" should "be able to down a file from gcs and write to local path" in {
    forAll {
      (localObjectPath: RelativePath, gsPath: GsPath) =>
        val bodyBytes = "this is great!".getBytes("UTF-8")
        val googleStorage = GoogleStorageAlg.fromGoogle(GoogleStorageAlgConfig(Paths.get("/tmp")), FakeGoogleStorageInterpreter)
        val localAbsolutePath = Paths.get(s"/tmp/${localObjectPath.asPath.toString}")
        // Create the local base directory
        val directory = new File(s"${localAbsolutePath.getParent.toString}")
        if (!directory.exists) {
          directory.mkdirs
        }
        val res = for {
          _ <- FakeGoogleStorageInterpreter.createBlob(gsPath.bucketName, gsPath.blobName, bodyBytes, "text/plain", Map.empty, None)
          resp <- googleStorage.gcsToLocalFile(localAbsolutePath, gsPath, TraceId(randomUUID()))
          _ <- Stream.eval(IO((new File(localAbsolutePath.toString)).delete()))
        } yield {
          val expectedCrc32c = Crc32c.calculateCrc32c(bodyBytes)
          resp shouldBe AdaptedGcsMetadata(None, expectedCrc32c, 0L)
        }
        res.compile.drain.unsafeRunSync()
    }
  }
}


object GoogleStorageServiceWithFailures extends BaseFakeGoogleStorage {
  override def createBlob(bucketName: GcsBucketName, objectName: GcsBlobName, objectContents: Array[Byte], objectType: String, metadata: Map[String, String], generation: Option[Long], traceId: Option[TraceId]): Stream[IO, Blob] = {
    val errors = new GoogleJsonError()
    errors.setCode(412)
    Stream.raiseError[IO](new com.google.cloud.storage.StorageException(errors))
  }
}