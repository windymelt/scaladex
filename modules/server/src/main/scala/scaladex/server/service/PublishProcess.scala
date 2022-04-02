package scaladex.server.service

import akka.actor.ActorSystem

import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import com.typesafe.scalalogging.LazyLogging
import scaladex.core.model.Env
import scaladex.core.model.Project
import scaladex.core.model.Sha1
import scaladex.core.model.UserState
import scaladex.core.service.{GithubService, Storage, WebDatabase}
import scaladex.data.cleanup.GithubRepoExtractor
import scaladex.data.maven.ArtifactModel
import scaladex.data.maven.PomsReader
import scaladex.data.meta.ArtifactConverter
import scaladex.infra.{CoursierResolver, DataPaths, GithubClient}

sealed trait PublishResult
object PublishResult {
  object InvalidPom extends PublishResult
  object NoGithubRepo extends PublishResult
  object Success extends PublishResult
  case class Forbidden(login: String, repo: Project.Reference) extends PublishResult
}

class PublishProcess(
    filesystem: Storage,
    githubExtractor: GithubRepoExtractor,
    converter: ArtifactConverter,
    database: WebDatabase,
    pomsReader: PomsReader,
    env: Env
)(implicit ec: ExecutionContext, system: ActorSystem)
    extends LazyLogging {
  def publishPom(
      path: String,
      data: String,
      creationDate: Instant,
      userState: Option[UserState]
  ): Future[PublishResult] = {
    logger.info(s"Publishing POM $path")
    val sha1 = Sha1(data)
    val tempFile = filesystem.createTempFile(data, sha1, ".pom")
    val future =
      Future(pomsReader.loadOne(tempFile).get)
        .flatMap { case (pom, _) => publishPom(pom, creationDate, userState) }
        .recover { cause =>
          logger.error(s"Invalid POM $path", cause)
          PublishResult.InvalidPom
        }
    future.onComplete(_ => filesystem.deleteTempFile(tempFile))
    future
  }

  private def publishPom(
      pom: ArtifactModel,
      creationDate: Instant,
      userState: Option[UserState]
  ): Future[PublishResult] = {
    val pomRef = s"${pom.groupId}:${pom.artifactId}:${pom.version}"
    githubExtractor.extract(pom) match {
      case None =>
        // TODO: save artifact with no github information
        Future.successful(PublishResult.NoGithubRepo)
      case Some(repo) =>
        // userState can be empty when the request of publish is done through the scheduler
        if (userState.isEmpty || userState.get.hasPublishingAuthority(env) || userState.get.repos.contains(repo)) {
          converter.convert(pom, repo, creationDate) match {
            case Some((artifact, deps)) =>
              val githubClient = userState.map(state => new GithubClient(state.info.token))
              for {
                isNewProject <- database.insertArtifact(artifact, deps, Instant.now)
                _ <-
                  if (isNewProject && githubClient.nonEmpty)
                    updateGithubInfo(githubClient.get, artifact.projectRef, Instant.now())
                  else Future.successful(())
              } yield {
                logger.info(s"Published $pomRef")
                PublishResult.Success
              }
            case None =>
              logger.warn(s"Cannot convert $pomRef to valid Scala artifact.")
              Future.successful(PublishResult.InvalidPom)
          }
        } else {
          logger.warn(s"User ${userState.get.info.login} attempted to publish to $repo")
          Future.successful(PublishResult.Forbidden(userState.get.info.login, repo))
        }
    }
  }
  private def updateGithubInfo(githubClient: GithubClient, ref: Project.Reference, now: Instant): Future[Unit] =
    for {
      githubReponse <- githubClient.getProjectInfo(ref)
      _ <- database.updateGithubInfo(ref, githubReponse, now)
    } yield ()

}

object PublishProcess {
  def apply(paths: DataPaths, filesystem: Storage, database: WebDatabase, env: Env)(
      implicit ec: ExecutionContext,
      actorSystem: ActorSystem
  ): PublishProcess = {
    val githubExtractor = new GithubRepoExtractor(paths)
    val converter = new ArtifactConverter(paths)
    val pomsReader = new PomsReader(new CoursierResolver)
    new PublishProcess(filesystem, githubExtractor, converter, database, pomsReader, env)
  }
}
