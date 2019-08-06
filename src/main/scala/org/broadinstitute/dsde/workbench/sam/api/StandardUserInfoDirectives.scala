package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directives.{headerValueByName, onSuccess, optionalHeaderValueByName}
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.BasicDirectives.provide
import akka.http.scaladsl.server.directives.OnSuccessMagnet._
import akka.http.scaladsl.server.directives.RouteDirectives.reject
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.google.ServiceAccountSubjectId
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, _}
import org.broadinstitute.dsde.workbench.sam.api.StandardUserInfoDirectives._
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.service.UserService._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

trait StandardUserInfoDirectives extends UserInfoDirectives {
  implicit val executionContext: ExecutionContext

  def requireUserInfo: Directive1[UserInfo] =
    (
      headerValueByName(accessTokenHeader) &
        callerId &
        headerValueByName(expiresInHeader) &
        headerValueByName(emailHeader)
    ) tflatMap {
      case (token, googleSubjectId, expiresIn, email) =>
        onSuccess(
          Try(expiresIn.toLong).fold[Future[UserInfo]](
            t =>
              Future.failed(
                new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"expiresIn $expiresIn can't be converted to Long because  of $t"))),
            l => getUserInfo(OAuth2BearerToken(token), GoogleSubjectId(googleSubjectId), WorkbenchEmail(email), l, directoryDAO).unsafeToFuture()
          ))
    }

  def requireCreateUser: Directive1[CreateWorkbenchUser] =
    (
      callerId &
        headerValueByName(emailHeader)
    ) tflatMap {
      case (googleSubjectId, email) => {
        val realEmail = if (!email.contains("@")) {
          email + "@example.com"
        } else {
          email
        }
        onSuccess(
          Future.successful(CreateWorkbenchUser(genWorkbenchUserId(System.currentTimeMillis()), GoogleSubjectId(googleSubjectId), WorkbenchEmail(realEmail))))
      }
    }

  def callerId: Directive1[String] = {
    (optionalHeaderValueByName(googleSubjectIdHeader) & optionalHeaderValueByName(clientIdHeader)).tflatMap {
      case (Some(uid), _) => provide(uid)
      case (None, Some(cid)) => provide(cid)
      case (None, None) => reject(MissingHeaderRejection(googleSubjectIdHeader))
    }
  }
}

object StandardUserInfoDirectives {
  val SAdomain = "\\S+@\\S+\\.iam\\.gserviceaccount\\.com".r
  val accessTokenHeader = "OIDC_access_token"
  val expiresInHeader = "OIDC_CLAIM_exp"
  val emailHeader = "OIDC_CLAIM_sub"
  val googleSubjectIdHeader = "OIDC_CLAIM_uid"
  val clientIdHeader = "OIDC_CLAIM_cid"

  def isServiceAccount(email: WorkbenchEmail) =
    SAdomain.pattern.matcher(email.value).matches

  def getUserInfo(
      token: OAuth2BearerToken,
      googleSubjectId: GoogleSubjectId,
      email: WorkbenchEmail,
      expiresIn: Long,
      directoryDAO: DirectoryDAO): IO[UserInfo] =
    if (isServiceAccount(email)) {
      // If it's a PET account, we treat it as its owner
      directoryDAO.getUserFromPetServiceAccount(ServiceAccountSubjectId(googleSubjectId.value)).flatMap {
        case Some(pet) => IO.pure(UserInfo(token, pet.id, pet.email, expiresIn.toLong))
        case None => lookUpByGoogleSubjectId(googleSubjectId, directoryDAO).map(uid => UserInfo(token, uid, email, expiresIn))
      }
    } else {
      lookUpByGoogleSubjectId(googleSubjectId, directoryDAO).map(uid => UserInfo(token, uid, email, expiresIn))
    }

  private def lookUpByGoogleSubjectId(googleSubjectId: GoogleSubjectId, directoryDAO: DirectoryDAO): IO[WorkbenchUserId] =
    for {
      subject <- directoryDAO.loadSubjectFromGoogleSubjectId(googleSubjectId)
      userInfo <- subject match {
        case Some(uid: WorkbenchUserId) => IO.pure(uid)
        case Some(_) =>
          IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"subjectId $googleSubjectId is not a WorkbenchUser")))
        case None =>
          IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"google subject Id $googleSubjectId not found in sam")))
      }
    } yield userInfo
}

final case class CreateWorkbenchUser(id: WorkbenchUserId, googleSubjectId: GoogleSubjectId, email: WorkbenchEmail)
