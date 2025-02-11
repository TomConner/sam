package org.broadinstitute.dsde.workbench.sam.dataAccess

import akka.http.scaladsl.model.StatusCodes
import cats.effect.{IO, Temporal}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccount, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.azure.{
  ActionManagedIdentity,
  ActionManagedIdentityId,
  BillingProfileId,
  ManagedIdentityDisplayName,
  ManagedIdentityObjectId,
  ManagedResourceGroupCoordinates,
  ManagedResourceGroupName,
  PetManagedIdentity,
  PetManagedIdentityId,
  SubscriptionId,
  TenantId
}
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory._
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders._
import org.broadinstitute.dsde.workbench.sam.db._
import org.broadinstitute.dsde.workbench.sam.db.tables._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.model.api.{AdminUpdateUserRequest, SamUser, SamUserAttributes}
import org.broadinstitute.dsde.workbench.sam.util.{DatabaseSupport, SamRequestContext}
import org.postgresql.util.PSQLException
import scalikejdbc._

import java.time.Instant
import java.util.Date
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Try}

class PostgresDirectoryDAO(protected val writeDbRef: DbReference, protected val readDbRef: DbReference)(implicit timer: Temporal[IO])
    extends DirectoryDAO
    with DatabaseSupport
    with PostgresGroupDAO
    with LazyLogging {

  override def createGroup(group: BasicWorkbenchGroup, accessInstructionsOpt: Option[String], samRequestContext: SamRequestContext): IO[BasicWorkbenchGroup] =
    serializableWriteTransaction("createGroup", samRequestContext) { implicit session =>
      val groupId: GroupPK = insertGroup(group)

      accessInstructionsOpt.map { accessInstructions =>
        insertAccessInstructions(groupId, accessInstructions)
      }

      insertGroupMembers(groupId, group.members)

      group
    }

  private def insertGroup(group: BasicWorkbenchGroup)(implicit session: DBSession): GroupPK = {
    val groupTableColumn = GroupTable.column
    val insertGroupQuery =
      samsql"""insert into ${GroupTable.table} (${groupTableColumn.name}, ${groupTableColumn.email}, ${groupTableColumn.updatedDate}, ${groupTableColumn.synchronizedDate})
           values (${group.id}, ${group.email}, ${Option(Instant.now())}, ${None})"""

    Try {
      GroupPK(insertGroupQuery.updateAndReturnGeneratedKey().apply())
    }.recoverWith {
      case duplicateException: PSQLException if duplicateException.getSQLState == PSQLStateExtensions.UNIQUE_VIOLATION =>
        Failure(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"group name ${group.id.value} already exists")))
    }.get
  }

  private def insertAccessInstructions(groupId: GroupPK, accessInstructions: String)(implicit session: DBSession): Int = {
    val accessInstructionsColumn = AccessInstructionsTable.column
    val insertAccessInstructionsQuery =
      samsql"insert into ${AccessInstructionsTable.table} (${accessInstructionsColumn.groupId}, ${accessInstructionsColumn.instructions}) values (${groupId}, ${accessInstructions})"

    insertAccessInstructionsQuery.update().apply()
  }

  override def loadGroup(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Option[BasicWorkbenchGroup]] =
    for {
      results <- readOnlyTransaction("loadGroup", samRequestContext) { implicit session =>
        val g = GroupTable.syntax("g")
        val sg = GroupTable.syntax("sg")
        val gm = GroupMemberTable.syntax("gm")
        val p = PolicyTable.syntax("p")
        val r = ResourceTable.syntax("r")
        val rt = ResourceTypeTable.syntax("rt")

        samsql"""select ${g.result.email}, ${gm.result.memberUserId}, ${sg.result.name}, ${p.result.name}, ${r.result.name}, ${rt.result.name}, ${g.result.version}, ${g.result.lastSynchronizedVersion}
                  from ${GroupTable as g}
                  left join ${GroupMemberTable as gm} on ${g.id} = ${gm.groupId}
                  left join ${GroupTable as sg} on ${gm.memberGroupId} = ${sg.id}
                  left join ${PolicyTable as p} on ${p.groupId} = ${sg.id}
                  left join ${ResourceTable as r} on ${p.resourceId} = ${r.id}
                  left join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
                  where ${g.name} = ${groupName}"""
          .map { rs =>
            (
              rs.get[WorkbenchEmail](g.resultName.email),
              rs.stringOpt(gm.resultName.memberUserId).map(WorkbenchUserId),
              rs.stringOpt(sg.resultName.name).map(WorkbenchGroupName),
              rs.stringOpt(p.resultName.name).map(AccessPolicyName(_)),
              rs.stringOpt(r.resultName.name).map(ResourceId(_)),
              rs.stringOpt(rt.resultName.name).map(ResourceTypeName(_)),
              rs.get[Int](g.resultName.version),
              rs.get[Option[Int]](g.resultName.lastSynchronizedVersion)
            )
          }
          .list()
          .apply()
      }
    } yield
      if (results.isEmpty) {
        None
      } else {
        val email = results.head._1
        val members: Set[WorkbenchSubject] = results.collect {
          case (_, Some(userId), None, None, None, None, _, _) => userId
          case (_, None, Some(subGroupName), None, None, None, _, _) => subGroupName
          case (_, None, Some(_), Some(policyName), Some(resourceName), Some(resourceTypeName), _, _) =>
            FullyQualifiedPolicyId(FullyQualifiedResourceId(resourceTypeName, resourceName), policyName)
        }.toSet

        val version = results.head._7
        val lastSynchronized = results.head._8

        Option(BasicWorkbenchGroup(groupName, members, email, version, lastSynchronized))
      }

  override def loadGroupEmail(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Option[WorkbenchEmail]] =
    batchLoadGroupEmail(Set(groupName), samRequestContext).map(_.toMap.get(groupName))

  override def batchLoadGroupEmail(
      groupNames: Set[WorkbenchGroupName],
      samRequestContext: SamRequestContext
  ): IO[LazyList[(WorkbenchGroupName, WorkbenchEmail)]] =
    if (groupNames.isEmpty) {
      IO.pure(LazyList.empty)
    } else {
      readOnlyTransaction("batchLoadGroupEmail", samRequestContext) { implicit session =>
        val g = GroupTable.column

        samsql"select ${g.name}, ${g.email} from ${GroupTable.table} where ${g.name} in (${groupNames})"
          .map(rs => (rs.get[WorkbenchGroupName](g.name), rs.get[WorkbenchEmail](g.email)))
          .list()
          .apply()
          .to(LazyList)
      }
    }

  override def deleteGroup(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Unit] =
    serializableWriteTransaction("deleteGroup", samRequestContext) { implicit session =>
      deleteGroup(groupName)
    }

  /** @return
    *   true if the subject was added, false if it was already there
    */
  override def addGroupMember(groupId: WorkbenchGroupIdentity, addMember: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean] =
    serializableWriteTransaction("addGroupMember", samRequestContext) { implicit session =>
      val numberAdded = insertGroupMembers(queryForGroupPKs(Set(groupId)).head, Set(addMember))
      if (numberAdded > 0) {
        updateGroupUpdatedDateAndVersion(groupId)
        true
      } else {
        false
      }
    }

  /** @return
    *   true if the subject was removed, false if it was already gone
    */
  override def removeGroupMember(groupId: WorkbenchGroupIdentity, removeMember: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean] =
    serializableWriteTransaction("removeGroupMember", samRequestContext) { implicit session =>
      val removed = removeGroupMember(groupId, removeMember)

      if (removed) {
        updateGroupUpdatedDateAndVersion(groupId)
      }

      removed
    }

  override def updateGroupUpdatedDateAndVersionWithSession(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Unit] =
    serializableWriteTransaction("updateGroupUpdatedDateAndVersionWithSession", samRequestContext) { implicit session =>
      updateGroupUpdatedDateAndVersion(groupId)
    }

  override def isGroupMember(groupId: WorkbenchGroupIdentity, member: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean] =
    readOnlyTransaction("isGroupMember", samRequestContext) { implicit session =>
      isGroupMember(groupId, member)
    }

  /*
    Update last synchronized version only when it is less than the current group version. This is to avoid
    threads stepping over each other and causing sam to become out of sync with google. The last synchronized version
    should only be set to the version of the group that is help in memory from when the sync started.
   */
  override def updateSynchronizedDateAndVersion(group: WorkbenchGroup, samRequestContext: SamRequestContext): IO[Unit] =
    serializableWriteTransaction("updateSynchronizedDateAndVersion", samRequestContext) { implicit session =>
      val g = GroupTable.column
      samsql"""update ${GroupTable.table}
              set ${g.synchronizedDate} = ${Instant.now()},
                  ${g.lastSynchronizedVersion} = ${group.version}
              where ${g.id} = (${workbenchGroupIdentityToGroupPK(group.id)}) and COALESCE(${g.lastSynchronizedVersion}, 0) < ${group.version}"""
        .update()
        .apply()
    }

  override def getSynchronizedDate(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Option[Date]] =
    readOnlyTransaction("getSynchronizedDate", samRequestContext) { implicit session =>
      val g = GroupTable.column
      samsql"select ${g.synchronizedDate} from ${GroupTable.table} where ${g.id} = (${workbenchGroupIdentityToGroupPK(groupId)})"
        .map(rs => rs.timestampOpt(g.synchronizedDate).map(_.toJavaUtilDate))
        .single()
        .apply()
        .getOrElse(throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"$groupId not found")))
    }

  override def getSynchronizedEmail(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Option[WorkbenchEmail]] =
    readOnlyTransaction("getSynchronizedEmail", samRequestContext) { implicit session =>
      val g = GroupTable.column

      samsql"select ${g.email} from ${GroupTable.table} where ${g.id} = (${workbenchGroupIdentityToGroupPK(groupId)})"
        .map(rs => rs.get[Option[WorkbenchEmail]](g.email))
        .single()
        .apply()
        .getOrElse(throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"$groupId not found")))
    }

  /*
    This query is better than it looks. The problem that this gets around is that we are given an email address,
    which can be for a user, a group, a policy, or a pet service account. We have no definitive way of knowing
    what type it belongs to until we query all four of the tables. You can't do a clean union here because the
    data in the four tables is shaped differently. Hence the nulls. The nulls coerce the data being selected from
    the tables to have the correct shape, and thus make it unionable. The advantage that this give us is that we
    only need to do one trip to the database to figure out what type it is.

    The alternative would be to have a "cleaner" looking function which actually just fires off a specialized query
    each of the four tables.
   */

  override def loadSubjectFromEmail(email: WorkbenchEmail, samRequestContext: SamRequestContext): IO[Option[WorkbenchSubject]] =
    readOnlyTransaction("loadSubjectFromEmail", samRequestContext) { implicit session =>
      val u = UserTable.syntax
      val g = GroupTable.syntax
      val pet = PetServiceAccountTable.syntax
      val pol = PolicyTable.syntax
      val srt = ResourceTypeTable.syntax
      val res = ResourceTable.syntax

      val query = samsql"""
              select ${u.id}, ${None}, ${None}, ${None}, ${None}, ${None}, ${None}
                from ${UserTable as u}
                where ${u.email} = ${email}
              union
              select ${None}, ${g.name}, ${None}, ${None}, ${None}, ${None}, ${None}
                from ${GroupTable as g}
                where ${g.email} = ${email}
              union
              select ${None}, ${None}, ${pet.userId}, ${pet.project}, ${None}, ${None}, ${None}
                from ${PetServiceAccountTable as pet}
                where ${pet.email} = ${email}
              union
              select ${None}, ${None}, ${None}, ${None}, ${srt.name}, ${res.name}, ${pol.name}
                from ${PolicyTable as pol}
                join ${GroupTable as g}
                on ${pol.groupId} = ${g.id}
                join ${ResourceTable as res}
                on ${res.id} = ${pol.resourceId}
                join ${ResourceTypeTable as srt}
                on ${res.resourceTypeId} = ${srt.id}
                where ${g.email} = ${email}"""

      val result = query
        .map(rs =>
          SubjectConglomerate(
            rs.stringOpt(1).map(WorkbenchUserId),
            rs.stringOpt(2).map(WorkbenchGroupName),
            rs.stringOpt(3).map(WorkbenchUserId),
            rs.stringOpt(4).map(GoogleProject),
            rs.stringOpt(5).map(name => ResourceTypeName(name)),
            rs.stringOpt(6).map(id => ResourceId(id)),
            rs.stringOpt(7).map(name => AccessPolicyName(name))
          )
        )
        .list()
        .apply()

      // The typical cases are the first two. However, if the subject being loaded is a policy, it will return
      // two rows- one for the underlying group, and one for the policy itself. An alternative is to resolve this
      // within the query itself.
      result.map(unmarshalSubjectConglomerate) match {
        case List() => None
        case List(subject) => Some(subject)
        case List(_: WorkbenchGroupName, policySubject: FullyQualifiedPolicyId) => Some(policySubject)
        case List(policySubject: FullyQualifiedPolicyId, _: WorkbenchGroupName) => Some(policySubject) // order in unions isn't guaranteed so support both cases
        case _ => throw new WorkbenchException(s"Database error: email $email refers to too many subjects.")

      }
    }

  def loadPolicyEmail(policyId: FullyQualifiedPolicyId, samRequestContext: SamRequestContext): IO[Option[WorkbenchEmail]] =
    readOnlyTransaction("loadPolicyEmail", samRequestContext) { implicit session =>
      val g = GroupTable.syntax
      val pol = PolicyTable.syntax
      val srt = ResourceTypeTable.syntax
      val res = ResourceTable.syntax

      val query = samsql"""
                     select ${g.result.email}
                     from ${PolicyTable as pol}
                     join ${GroupTable as g}
                      on ${pol.groupId} = ${g.id}
                     join ${ResourceTable as res}
                      on ${res.id} = ${pol.resourceId}
                     join ${ResourceTypeTable as srt}
                      on ${res.resourceTypeId} = ${srt.id}
                     where ${srt.name} = ${policyId.resource.resourceTypeName} and
                      ${res.name} = ${policyId.resource.resourceId} and
                      ${pol.name} = ${policyId.accessPolicyName}"""

      query.map(rs => rs.get[WorkbenchEmail](g.resultName.email)).single().apply()
    }

  override def loadSubjectEmail(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Option[WorkbenchEmail]] =
    subject match {
      case subject: WorkbenchGroupName => loadGroupEmail(subject, samRequestContext)
      case subject: PetServiceAccountId =>
        for {
          petSA <- loadPetServiceAccount(subject, samRequestContext)
        } yield petSA.map(_.serviceAccount.email)
      case subject: WorkbenchUserId =>
        for {
          user <- loadUser(subject, samRequestContext)
        } yield user.map(_.email)
      case subject: FullyQualifiedPolicyId => loadPolicyEmail(subject, samRequestContext)
      case _ => throw new WorkbenchException(s"unexpected subject [$subject]")
    }

  override def loadSubjectFromGoogleSubjectId(googleSubjectId: GoogleSubjectId, samRequestContext: SamRequestContext): IO[Option[WorkbenchSubject]] =
    readOnlyTransaction("loadSubjectFromGoogleSubjectId", samRequestContext) { implicit session =>
      val u = UserTable.syntax
      val pet = PetServiceAccountTable.syntax

      // Only pets and users can have googleSubjectIds so we won't bother checking for the other types of WorkbenchSubjects
      val query = samsql"""
              select ${u.id}, ${None}, ${None} from ${UserTable as u}
                where ${u.googleSubjectId} = ${googleSubjectId}
              union
              select ${None}, ${pet.userId}, ${pet.project} from ${PetServiceAccountTable as pet}
                where ${pet.googleSubjectId} = ${googleSubjectId}"""

      val result = query
        .map(rs =>
          SubjectConglomerate(
            rs.stringOpt(1).map(WorkbenchUserId),
            None,
            rs.stringOpt(2).map(WorkbenchUserId),
            rs.stringOpt(3).map(GoogleProject),
            None,
            None,
            None
          )
        )
        .single()
        .apply()

      result.map(unmarshalSubjectConglomerate)
    }

  override def createUser(user: SamUser, samRequestContext: SamRequestContext): IO[SamUser] =
    serializableWriteTransaction("createUser", samRequestContext) { implicit session =>
      val newUser = user.copy(
        createdAt = maybeAdjustDate(user.createdAt),
        updatedAt = maybeAdjustDate(user.updatedAt)
      )

      val userColumn = UserTable.column

      val insertUserQuery =
        samsql"""insert into ${UserTable.table}
                 (${userColumn.id},
                  ${userColumn.email},
                  ${userColumn.googleSubjectId},
                  ${userColumn.enabled},
                  ${userColumn.azureB2cId},
                  ${userColumn.createdAt},
                  ${userColumn.registeredAt},
                  ${userColumn.updatedAt})
                 values
                 (${newUser.id},
                  ${newUser.email},
                  ${newUser.googleSubjectId},
                  ${newUser.enabled},
                  ${newUser.azureB2CId},
                  ${newUser.createdAt},
                  ${newUser.registeredAt},
                  ${newUser.updatedAt})"""

      Try {
        insertUserQuery.update().apply()
      }.recoverWith {
        case duplicateException: PSQLException if duplicateException.getSQLState == PSQLStateExtensions.UNIQUE_VIOLATION =>
          Failure(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"identity with id ${newUser.id} already exists")))
      }.get
      newUser
    }

  private def maybeAdjustDate(instant: Instant): Instant =
    if (instant.equals(Instant.EPOCH) || instant.isBefore(Instant.EPOCH)) Instant.now() else instant

  override def loadUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[SamUser]] =
    readOnlyTransaction("loadUser", samRequestContext) { implicit session =>
      val userTable = UserTable.syntax

      val loadUserQuery = samsql"select ${userTable.resultAll} from ${UserTable as userTable} where ${userTable.id} = ${userId}"
      loadUserQuery
        .map(UserTable(userTable))
        .single()
        .apply()
        .map(UserTable.unmarshalUserRecord)
    }

  override def batchLoadUsers(
      samUserIds: Set[WorkbenchUserId],
      samRequestContext: SamRequestContext
  ): IO[Seq[SamUser]] =
    if (samUserIds.isEmpty) {
      IO.pure(Seq.empty)
    } else {
      readOnlyTransaction("batchLoadUsers", samRequestContext) { implicit session =>
        val userTable = UserTable.syntax
        val loadUserQuery = samsql"select ${userTable.resultAll} from ${UserTable as userTable} where ${userTable.id} in (${samUserIds})"

        loadUserQuery
          .map(UserTable(userTable))
          .list()
          .apply()
          .map(UserTable.unmarshalUserRecord)
      }
    }

  override def loadUsersByQuery(
      userId: Option[WorkbenchUserId],
      googleSubjectId: Option[GoogleSubjectId],
      azureB2CId: Option[AzureB2CId],
      limit: Int,
      samRequestContext: SamRequestContext
  ): IO[Set[SamUser]] =
    readOnlyTransaction("loadUsersByQuery", samRequestContext) { implicit session =>
      val userTable = UserTable.syntax
      val loadUserQuery =
        samsql"""select ${userTable.resultAll} from ${UserTable as userTable}
                where
                ${userTable.id} = $userId
                OR ${userTable.googleSubjectId} = $googleSubjectId
                OR ${userTable.azureB2cId} = $azureB2CId
                ORDER BY
                ${userTable.id}, ${userTable.googleSubjectId}, ${userTable.azureB2cId}
                ASC NULLS LAST LIMIT $limit"""
      loadUserQuery
        .map(UserTable(userTable))
        .list()
        .apply()
        .map(UserTable.unmarshalUserRecord)
        .toSet
    }

  override def loadUserByGoogleSubjectId(userId: GoogleSubjectId, samRequestContext: SamRequestContext): IO[Option[SamUser]] =
    readOnlyTransaction("loadUserByGoogleSubjectId", samRequestContext) { implicit session =>
      val userTable = UserTable.syntax

      val loadUserQuery = samsql"select ${userTable.resultAll} from ${UserTable as userTable} where ${userTable.googleSubjectId} = ${userId}"
      loadUserQuery
        .map(UserTable(userTable))
        .single()
        .apply()
        .map(UserTable.unmarshalUserRecord)
    }

  override def loadUserByAzureB2CId(userId: AzureB2CId, samRequestContext: SamRequestContext): IO[Option[SamUser]] =
    readOnlyTransaction("loadUserByAzureB2CId", samRequestContext) { implicit session =>
      val userTable = UserTable.syntax

      val loadUserQuery = samsql"select ${userTable.resultAll} from ${UserTable as userTable} where ${userTable.azureB2cId} = ${userId}"
      loadUserQuery
        .map(UserTable(userTable))
        .single()
        .apply()
        .map(UserTable.unmarshalUserRecord)
    }

  // Note that this method is a little different from setGoogleSubjectId in that this method will still perform the
  // update even if the azureB2CId is the same value.  This begs questions about the exception message and whether or
  // not the `updatedAt` datetime should be set.  For now, we're going on the assumption that yes, the database is
  // being updated (even if no values are changing) so we're setting the `updatedAt` datetime
  override def setUserAzureB2CId(userId: WorkbenchUserId, b2cId: AzureB2CId, samRequestContext: SamRequestContext): IO[Unit] =
    serializableWriteTransaction("setUserAzureB2CId", samRequestContext) { implicit session =>
      val u = UserTable.column
      val results =
        samsql"""update ${UserTable.table}
                 set (${u.azureB2cId}, ${u.updatedAt}) =
                 ($b2cId,
                   ${Instant.now()}
                 )
                 where ${u.id} = $userId and (${u.azureB2cId} is null or ${u.azureB2cId} = $b2cId)"""
          .update()
          .apply()

      if (results != 1) {
        throw new WorkbenchException(
          s"Cannot update azureB2cId for user ${userId} because user does not exist or the azureB2cId has already been set for this user"
        )
      } else {
        ()
      }
    }

  override def updateUserEmail(userId: WorkbenchUserId, email: WorkbenchEmail, samRequestContext: SamRequestContext): IO[Unit] = IO.unit

  override def updateUser(samUser: SamUser, userUpdate: AdminUpdateUserRequest, samRequestContext: SamRequestContext): IO[Option[SamUser]] =
    // NOTE updating emails and 'enabled' status is currently not supported by this method
    serializableWriteTransaction("updateUser", samRequestContext) { implicit session =>
      val u = UserTable.column

      if (userUpdate.googleSubjectId.isEmpty && userUpdate.azureB2CId.isEmpty) {
        throw new WorkbenchException("Cannot update user with no values.")
      }

      val (updateGoogleColumn, updateGoogleValue, returnGoogleValue) = userUpdate.googleSubjectId match {
        case None => (None, None, samUser.googleSubjectId)
        case Some(GoogleSubjectId("null")) =>
          (Some(samsqls"${u.googleSubjectId}"), Some(samsqls"null"), None)
        case Some(newGoogleSubjectId: GoogleSubjectId) =>
          (Some(samsqls"${u.googleSubjectId}"), Some(samsqls"$newGoogleSubjectId"), Some(newGoogleSubjectId))
      }

      val (updateAzureB2CColumn, updateAzureB2CValue, returnAzureB2CValue) = userUpdate.azureB2CId match {
        case None => (None, None, samUser.azureB2CId)
        case Some(AzureB2CId("null")) =>
          (Some(samsqls"${u.azureB2cId}"), Some(samsqls"null"), None)
        case Some(newAzureB2CId: AzureB2CId) =>
          (Some(samsqls"${u.azureB2cId}"), Some(samsqls"$newAzureB2CId"), Some(newAzureB2CId))
      }

      // This is a little hacky, but is needed because SQLSyntax's `flatten`, `substring`, and other string-manipulation
      // methods transform the SQLSyntax into a String. Thankfully, since we always have an `updatedAt` value,
      // we can use it as a base for foldLeft, and then concatenate the rest of the existing values to it
      // within the `samsqls` interpolation, preserving the SQLSyntax functionality.
      val updateColumns = List(updateGoogleColumn, updateAzureB2CColumn).flatten
        .foldLeft(samsqls"${u.updatedAt}")((acc, col) => samsqls"$acc, $col")
      val updateValues = List(updateGoogleValue, updateAzureB2CValue).flatten
        .foldLeft(samsqls"${Instant.now()}")((acc, col) => samsqls"$acc, $col")

      val results = samsql"""update ${UserTable.table}
               set ($updateColumns) = ($updateValues)
               where ${u.id} = ${samUser.id}"""
        .update()
        .apply()

      if (results != 1) {
        None
      } else {
        Option(
          samUser.copy(
            googleSubjectId = returnGoogleValue,
            azureB2CId = returnAzureB2CValue,
            updatedAt = Instant.now()
          )
        )
      }
    }

  override def deleteUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Unit] =
    serializableWriteTransaction("deleteUser", samRequestContext) { implicit session =>
      val userTable = UserTable.syntax
      samsql"delete from ${UserTable.table} where ${userTable.id} = ${userId}".update().apply()
    }

  override def listUsersGroups(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Set[WorkbenchGroupIdentity]] =
    listMemberOfGroups(userId, samRequestContext)

  /** Extracts a WorkbenchGroupIdentity from a SQL query
    *
    * @param rs
    *   of form (groupName, Option[policyName], Option[resourceName], Option[resourceTypeName]
    * @param g
    *   SQLSyntaxProvider for GroupTable
    * @param p
    *   SQLSyntaxProvider for PolicyTable
    * @param r
    *   SQLSyntaxProvider for ResourceTable
    * @param rt
    *   SQLSyntaxProvider for ResourceTypeTable
    * @return
    *   Either a WorkbenchGroupName or a FullyQualifiedPolicyId if policy information is available
    */
  private def resultSetToGroupIdentity(
      rs: WrappedResultSet,
      g: QuerySQLSyntaxProvider[SQLSyntaxSupport[GroupRecord], GroupRecord],
      p: QuerySQLSyntaxProvider[SQLSyntaxSupport[PolicyRecord], PolicyRecord],
      r: QuerySQLSyntaxProvider[SQLSyntaxSupport[ResourceRecord], ResourceRecord],
      rt: QuerySQLSyntaxProvider[SQLSyntaxSupport[ResourceTypeRecord], ResourceTypeRecord]
  ): WorkbenchGroupIdentity =
    (rs.stringOpt(p.resultName.name), rs.stringOpt(r.resultName.name), rs.stringOpt(rt.resultName.name)) match {
      case (Some(policyName), Some(resourceId), Some(resourceTypeName)) =>
        FullyQualifiedPolicyId(FullyQualifiedResourceId(ResourceTypeName(resourceTypeName), ResourceId(resourceId)), AccessPolicyName(policyName))
      case (None, None, None) =>
        rs.get[WorkbenchGroupName](g.resultName.name)
      case (policyOpt, resourceOpt, resourceTypeOpt) =>
        throw new WorkbenchException(
          s"Inconsistent result. Expected either nothing or names for the policy, resource, and resource type, but instead got (policy = ${policyOpt}, resource = ${resourceOpt}, resourceType = ${resourceTypeOpt})"
        )
    }

  override def listUserDirectMemberships(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[LazyList[WorkbenchGroupIdentity]] =
    readOnlyTransaction("listUserDirectMemberships", samRequestContext) { implicit session =>
      val gm = GroupMemberTable.syntax("gm")
      val g = GroupTable.syntax("g")
      val p = PolicyTable.syntax("p")
      val r = ResourceTable.syntax("r")
      val rt = ResourceTypeTable.syntax("rt")

      samsql"""select ${g.result.name}, ${p.result.name}, ${r.result.name}, ${rt.result.name}
              from ${GroupTable as g}
              join ${GroupMemberTable as gm} on ${gm.groupId} = ${g.id}
              left join ${PolicyTable as p} on ${p.groupId} = ${g.id}
              left join ${ResourceTable as r} on ${p.resourceId} = ${r.id}
              left join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
              where ${gm.memberUserId} = ${userId}"""
        .map(resultSetToGroupIdentity(_, g, p, r, rt))
        .list()
        .apply()
        .to(LazyList)
    }

  /** This query attempts to list the User records that are members of ALL the groups given in the groupIds parameter.
    *
    * The overall approach is to take each group and flatten its membership to determine the full set of all the users that are a member of that group, its
    * children, its children's children, etc. Once we have the flattened group membership for each group, we do a big join statement to select only those users
    * that showed up in the flattened list for each group.
    *
    * The crux of this implementation is the use of PostgreSQL's `WITH` statement, https://www.postgresql.org/docs/9.6/queries-with.html These statements allow
    * us to create Common Table Expressions (CTE), which are basically temporary tables that exist only for the duration of the query. You can create multiple
    * CTEs using a single `WITH` by comma separating each `SELECT` that creates each individual CTE. In this implementation, we use `WITH RECURSIVE` to create N
    * CTEs where N is the cardinality of the groupIds parameter. Each of these CTEs is the flattened group membership for one of the WorkbenchGroupIdentities.
    *
    * The final part of the query intersects all the tables defined in the CTE to give us the final result of only those memberUserIds that showed up as an
    * entry in every CTE.
    * @param groupIds
    * @return
    *   Set of WorkbenchUserIds that are members of each group specified by groupIds
    */
  override def listIntersectionGroupUsers(groupIds: Set[WorkbenchGroupIdentity], samRequestContext: SamRequestContext): IO[Set[WorkbenchUserId]] =
    readOnlyTransaction("listIntersectionGroupUsers", samRequestContext) { implicit session =>
      val f = GroupMemberFlatTable.syntax("f")
      val groupMemberQueries = groupIds.map { groupId =>
        samsqls"select ${f.result.memberUserId} from ${GroupMemberFlatTable as f} where ${f.memberUserId} is not null and ${f.groupId} = (${workbenchGroupIdentityToGroupPK(groupId)})"
      }
      samsql"""${groupMemberQueries.reduce((left, right) => samsqls"$left intersect $right")}""".map(rs => WorkbenchUserId(rs.string(1))).list().apply().toSet
    }

  private def listMemberOfGroups(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Set[WorkbenchGroupIdentity]] = {
    val f = GroupMemberFlatTable.syntax("f")
    val g = GroupTable.syntax("g")
    val p = PolicyTable.syntax("p")

    val where = subject match {
      case userId: WorkbenchUserId => samsqls"where ${f.memberUserId} = ${userId}"
      case workbenchGroupIdentity: WorkbenchGroupIdentity => samsqls"where ${f.memberGroupId} = (${workbenchGroupIdentityToGroupPK(workbenchGroupIdentity)})"
      case _ => throw new WorkbenchException(s"Unexpected WorkbenchSubject. Expected WorkbenchUserId or WorkbenchGroupIdentity but got ${subject}")
    }

    readOnlyTransaction("listMemberOfGroups", samRequestContext) { implicit session =>
      val r = ResourceTable.syntax("r")
      val rt = ResourceTypeTable.syntax("rt")

      val listGroupsQuery =
        samsql"""select ${g.result.name}, ${p.result.name}, ${r.result.name}, ${rt.result.name}
                 from ${GroupMemberFlatTable as f}
            join ${GroupTable as g} on ${f.groupId} = ${g.id}
            left join ${PolicyTable as p} on ${p.groupId} = ${g.id}
            left join ${ResourceTable as r} on ${p.resourceId} = ${r.id}
            left join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
            ${where}"""

      listGroupsQuery.map(resultSetToGroupIdentity(_, g, p, r, rt)).list().apply().toSet
    }
  }

  override def listAncestorGroups(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Set[WorkbenchGroupIdentity]] =
    listMemberOfGroups(groupId, samRequestContext)

  override def listFlattenedGroupMembers(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Set[WorkbenchUserId]] = {
    val f = GroupMemberFlatTable.syntax("f")
    val g = GroupTable.syntax("g")

    readOnlyTransaction("listFlattenedGroupMembers", samRequestContext) { implicit session =>
      val query = samsql"""select distinct ${f.result.memberUserId}
        from ${GroupMemberFlatTable as f}
        join ${GroupTable as g} on ${g.id} = ${f.groupId}
        where ${g.name} = ${groupName}
        and ${f.memberUserId} is not null"""

      query.map(_.get[WorkbenchUserId](f.resultName.memberUserId)).list().apply().toSet
    }
  }

  override def enableIdentity(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Unit] =
    subject match {
      case userId: WorkbenchUserId =>
        serializableWriteTransaction("enableIdentity", samRequestContext) { implicit session =>
          val u = UserTable.column
          samsql"""update ${UserTable.table}
                   set (${u.enabled}, ${u.updatedAt}) =
                   (true, ${Instant.now()})
                   where ${u.id} = ${userId}""".update().apply()
        }
      case _ => IO.unit // other types of WorkbenchSubjects cannot be enabled
    }

  override def disableIdentity(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Unit] =
    serializableWriteTransaction("disableIdentity", samRequestContext) { implicit session =>
      subject match {
        case userId: WorkbenchUserId =>
          val u = UserTable.column
          samsql"""update ${UserTable.table}
                   set (${u.enabled}, ${u.updatedAt}) =
                   (false, ${Instant.now()})
                   where ${u.id} = ${userId}""".update().apply()
        case _ => // other types of WorkbenchSubjects cannot be disabled
      }
    }

  override def acceptTermsOfService(userId: WorkbenchUserId, tosVersion: String, samRequestContext: SamRequestContext): IO[Boolean] = {
    val tosTable = TosTable.syntax
    val tosColumns = TosTable.column
    serializableWriteTransaction("acceptTermsOfService", samRequestContext) { implicit session =>
      samsql"""insert into ${TosTable as tosTable} (${tosColumns.samUserId}, ${tosColumns.version}, ${tosColumns.action}, ${tosColumns.createdAt})
               values ($userId, $tosVersion, ${TosTable.ACCEPT}, ${Instant.now()})""".update().apply() > 0
    }
  }

  override def rejectTermsOfService(userId: WorkbenchUserId, tosVersion: String, samRequestContext: SamRequestContext): IO[Boolean] = {
    val tosTable = TosTable.syntax
    val tosColumns = TosTable.column
    serializableWriteTransaction("rejectTermsOfService", samRequestContext) { implicit session =>
      samsql"""insert into ${TosTable as tosTable} (${tosColumns.samUserId}, ${tosColumns.version}, ${tosColumns.action}, ${tosColumns.createdAt})
         values ($userId, $tosVersion, ${TosTable.REJECT}, ${Instant.now()})""".update().apply() > 0
    }
  }

  // When no tosVersion is specified, return the latest TosRecord for the user
  override def getUserTermsOfService(userId: WorkbenchUserId, samRequestContext: SamRequestContext, action: Option[String] = None): IO[Option[SamUserTos]] =
    getUserTermsOfServiceVersion(userId, None, samRequestContext, action)

  override def getUserTermsOfServiceVersion(
      userId: WorkbenchUserId,
      tosVersion: Option[String],
      samRequestContext: SamRequestContext,
      action: Option[String] = None
  ): IO[Option[SamUserTos]] =
    readOnlyTransaction("getUserTermsOfService", samRequestContext) { implicit session =>
      val tosTable = TosTable.syntax
      val column = TosTable.column

      val versionConstraint = tosVersion.map(v => samsqls"and ${column.version} = $v").getOrElse(samsqls"")
      val actionConstraint = action.map(a => samsqls"and ${column.action} = $a").getOrElse(samsqls"")

      val loadUserTosQuery =
        samsql"""select ${tosTable.resultAll}
              from ${TosTable as tosTable}
              where ${column.samUserId} = $userId
                $versionConstraint
                $actionConstraint
              order by ${column.createdAt} desc
              limit 1"""

      val userTosRecordOpt: Option[TosRecord] = loadUserTosQuery.map(TosTable(tosTable)).first().apply()
      userTosRecordOpt.map(TosTable.unmarshalUserRecord)
    }

  override def getUserTermsOfServiceHistory(userId: WorkbenchUserId, samRequestContext: SamRequestContext, limit: Integer): IO[List[SamUserTos]] =
    readOnlyTransaction("getUserTermsOfServiceHistory", samRequestContext) { implicit session =>
      val tosTable = TosTable.syntax
      val column = TosTable.column

      val loadUserTosQuery =
        samsql"""select ${tosTable.resultAll}
              from ${TosTable as tosTable}
              where ${column.samUserId} = ${userId}
              order by ${column.createdAt} desc
              limit ${limit}"""

      val userTosRecordOpt: List[TosRecord] = loadUserTosQuery.map(TosTable(tosTable)).list().apply()
      userTosRecordOpt.map(TosTable.unmarshalUserRecord)
    }

  override def isEnabled(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean] =
    readOnlyTransaction("isEnabled", samRequestContext) { implicit session =>
      val userIdOpt = subject match {
        case user: WorkbenchUserId => Option(user)
        case PetServiceAccountId(user, _) => Option(user)
        case _ => None
      }

      val u = UserTable.column

      userIdOpt
        .flatMap { userId =>
          samsql"select ${u.enabled} from ${UserTable.table} where ${u.id} = ${userId}"
            .map(rs => rs.boolean(u.enabled))
            .single()
            .apply()
        }
        .getOrElse(false)
    }

  override def getUserFromPetServiceAccount(petSA: ServiceAccountSubjectId, samRequestContext: SamRequestContext): IO[Option[SamUser]] =
    readOnlyTransaction("getUserFromPetServiceAccount", samRequestContext) { implicit session =>
      val petServiceAccountTable = PetServiceAccountTable.syntax
      val userTable = UserTable.syntax

      val loadUserQuery = samsql"""select ${userTable.resultAll}
                from ${UserTable as userTable}
                join ${PetServiceAccountTable as petServiceAccountTable} on ${petServiceAccountTable.userId} = ${userTable.id}
                where ${petServiceAccountTable.googleSubjectId} = ${petSA}"""

      val userRecordOpt: Option[UserRecord] = loadUserQuery.map(UserTable(userTable)).single().apply()
      userRecordOpt.map(UserTable.unmarshalUserRecord)
    }

  override def createPetServiceAccount(petServiceAccount: PetServiceAccount, samRequestContext: SamRequestContext): IO[PetServiceAccount] =
    serializableWriteTransaction("createPetServiceAccount", samRequestContext) { implicit session =>
      val petServiceAccountColumn = PetServiceAccountTable.column

      samsql"""insert into ${PetServiceAccountTable.table} (${petServiceAccountColumn.userId}, ${petServiceAccountColumn.project}, ${petServiceAccountColumn.googleSubjectId}, ${petServiceAccountColumn.email}, ${petServiceAccountColumn.displayName})
           values (${petServiceAccount.id.userId}, ${petServiceAccount.id.project}, ${petServiceAccount.serviceAccount.subjectId}, ${petServiceAccount.serviceAccount.email}, ${petServiceAccount.serviceAccount.displayName})"""
        .update()
        .apply()
      petServiceAccount
    }

  override def loadPetServiceAccount(petServiceAccountId: PetServiceAccountId, samRequestContext: SamRequestContext): IO[Option[PetServiceAccount]] =
    readOnlyTransaction("loadPetServiceAccount", samRequestContext) { implicit session =>
      val petServiceAccountTable = PetServiceAccountTable.syntax

      val loadPetQuery = samsql"""select ${petServiceAccountTable.resultAll}
       from ${PetServiceAccountTable as petServiceAccountTable}
       where ${petServiceAccountTable.userId} = ${petServiceAccountId.userId} and ${petServiceAccountTable.project} = ${petServiceAccountId.project}"""

      val petRecordOpt = loadPetQuery.map(PetServiceAccountTable(petServiceAccountTable)).single().apply()
      petRecordOpt.map(unmarshalPetServiceAccountRecord)
    }

  override def deletePetServiceAccount(petServiceAccountId: PetServiceAccountId, samRequestContext: SamRequestContext): IO[Unit] =
    serializableWriteTransaction("deletePetServiceAccount", samRequestContext) { implicit session =>
      val petServiceAccountTable = PetServiceAccountTable.syntax
      val deletePetQuery =
        samsql"delete from ${PetServiceAccountTable.table} where ${petServiceAccountTable.userId} = ${petServiceAccountId.userId} and ${petServiceAccountTable.project} = ${petServiceAccountId.project}"
      if (deletePetQuery.update().apply() != 1) {
        throw new WorkbenchException(s"${petServiceAccountId} cannot be deleted because it already does not exist")
      }
    }

  override def getAllPetServiceAccountsForUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Seq[PetServiceAccount]] =
    readOnlyTransaction("getAllPetServiceAccountsForUser", samRequestContext) { implicit session =>
      val petServiceAccountTable = PetServiceAccountTable.syntax

      val loadPetsQuery = samsql"""select ${petServiceAccountTable.resultAll}
                from ${PetServiceAccountTable as petServiceAccountTable} where ${petServiceAccountTable.userId} = ${userId}"""

      val petRecords = loadPetsQuery.map(PetServiceAccountTable(petServiceAccountTable)).list().apply()
      petRecords.map(unmarshalPetServiceAccountRecord)
    }

  override def updatePetServiceAccount(petServiceAccount: PetServiceAccount, samRequestContext: SamRequestContext): IO[PetServiceAccount] =
    serializableWriteTransaction("updatePetServiceAccount", samRequestContext) { implicit session =>
      val petServiceAccountColumn = PetServiceAccountTable.column
      val updatePetQuery = samsql"""update ${PetServiceAccountTable.table} set
        ${petServiceAccountColumn.googleSubjectId} = ${petServiceAccount.serviceAccount.subjectId},
        ${petServiceAccountColumn.email} = ${petServiceAccount.serviceAccount.email},
        ${petServiceAccountColumn.displayName} = ${petServiceAccount.serviceAccount.displayName}
        where ${petServiceAccountColumn.userId} = ${petServiceAccount.id.userId} and ${petServiceAccountColumn.project} = ${petServiceAccount.id.project}"""

      if (updatePetQuery.update().apply() != 1) {
        throw new WorkbenchException(s"Update cannot be applied because ${petServiceAccount.id} does not exist")
      }

      petServiceAccount
    }

  private def unmarshalPetServiceAccountRecord(petRecord: PetServiceAccountRecord): PetServiceAccount =
    PetServiceAccount(
      PetServiceAccountId(petRecord.userId, petRecord.project),
      ServiceAccount(petRecord.googleSubjectId, petRecord.email, petRecord.displayName)
    )

  case class SubjectConglomerate(
      userId: Option[WorkbenchUserId],
      groupName: Option[WorkbenchGroupName],
      petUserId: Option[WorkbenchUserId],
      petProject: Option[GoogleProject],
      policyResourceType: Option[ResourceTypeName],
      policyResourceId: Option[ResourceId],
      policyName: Option[AccessPolicyName]
  )

  private def unmarshalSubjectConglomerate(subjectConglomerate: SubjectConglomerate): WorkbenchSubject =
    subjectConglomerate match {
      case SubjectConglomerate(Some(userId), None, None, None, None, None, None) => userId
      case SubjectConglomerate(None, Some(groupName), None, None, None, None, None) => groupName
      case SubjectConglomerate(None, None, Some(petUserId), Some(petProject), None, None, None) => PetServiceAccountId(petUserId, petProject)
      case SubjectConglomerate(None, None, None, None, Some(policyResourceType), Some(policyResourceId), Some(policyName)) =>
        FullyQualifiedPolicyId(FullyQualifiedResourceId(policyResourceType, policyResourceId), policyName)
      case _ => throw new WorkbenchException("Not found")
    }

  override def getManagedGroupAccessInstructions(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Option[String]] =
    readOnlyTransaction("getManagedGroupAccessInstructions", samRequestContext) { implicit session =>
      val groupTable = GroupTable.syntax
      val accessInstructionsTable = AccessInstructionsTable.syntax

      // note the left join - this allows us to distinguish between the group does not exist and the group exists but
      // does not have access instructions
      val loadAccessInstructionsQuery = samsql"""select ${accessInstructionsTable.resultAll}
                from ${GroupTable as groupTable}
                left join ${AccessInstructionsTable as accessInstructionsTable} on ${groupTable.id} = ${accessInstructionsTable.groupId}
                where ${groupTable.name} = ${groupName}"""

      val accessInstructionsOpt = loadAccessInstructionsQuery.map(AccessInstructionsTable(accessInstructionsTable)).single().apply()

      accessInstructionsOpt match {
        case None =>
          // the group does not exist
          throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"$groupName not found"))
        case Some(accessInstructionsRecord) =>
          // the group exists but the access instructions will be null in the query result if there are no instructions
          Option(accessInstructionsRecord.instructions)
      }
    }

  override def setManagedGroupAccessInstructions(groupName: WorkbenchGroupName, accessInstructions: String, samRequestContext: SamRequestContext): IO[Unit] =
    serializableWriteTransaction("setManagedGroupAccessInstructions", samRequestContext) { implicit session =>
      val groupPKQuery = workbenchGroupIdentityToGroupPK(groupName)
      val accessInstructionsColumn = AccessInstructionsTable.column

      val upsertAccessInstructionsQuery = samsql"""insert into ${AccessInstructionsTable.table}
                            (${accessInstructionsColumn.groupId}, ${accessInstructionsColumn.instructions})
                            values((${groupPKQuery}), ${accessInstructions})
                            on conflict (${accessInstructionsColumn.groupId})
                            do update set ${accessInstructionsColumn.instructions} = ${accessInstructions}
                            where ${AccessInstructionsTable.syntax.groupId} = (${groupPKQuery})"""

      upsertAccessInstructionsQuery.update().apply()
    }

  override def setGoogleSubjectId(userId: WorkbenchUserId, googleSubjectId: GoogleSubjectId, samRequestContext: SamRequestContext): IO[Unit] =
    serializableWriteTransaction("setGoogleSubjectId", samRequestContext) { implicit session =>
      val u = UserTable.column
      val updateGoogleSubjectIdQuery =
        samsql"""update ${UserTable.table}
                 set (${u.googleSubjectId}, ${u.updatedAt}) =
                 (${googleSubjectId},
                   ${Instant.now()}
                 )
                 where ${u.id} = ${userId} and ${u.googleSubjectId} is null"""

      if (updateGoogleSubjectIdQuery.update().apply() != 1) {
        throw new WorkbenchException(
          s"Cannot update googleSubjectId for user ${userId} because user does not exist or the googleSubjectId has already been set for this user"
        )
      }
    }

  override def checkStatus(samRequestContext: SamRequestContext): IO[Boolean] =
    checkStatusWithQuery(samsqls"SELECT 1", samRequestContext)

  // This method exists so that we can test a _real_ db connection when the query fails for some reason
  // If we can find another way to have a valid connection pool but make the query fail, we can get rid of this method
  // and move its logic into the public `checkStatus`
  private[dataAccess] def checkStatusWithQuery(query: SQLSyntax, samRequestContext: SamRequestContext): IO[Boolean] =
    readOnlyTransaction("checkStatus", samRequestContext) { implicit session =>
      val isSessionValid = session.connection.isValid((2 seconds).toSeconds.intValue())
      val canQuery =
        samsql"$query"
          .map(rs => rs.int(1))
          .single()
          .apply()
          .nonEmpty

      isSessionValid && canQuery
    }.recoverWith { err =>
      logger.error("Failed to connect to Sam's database", err)
      IO(false)
    }

  override def createPetManagedIdentity(petManagedIdentity: PetManagedIdentity, samRequestContext: SamRequestContext): IO[PetManagedIdentity] =
    serializableWriteTransaction("createPetManagedIdentity", samRequestContext) { implicit session =>
      val petManagedIdentityColumn = PetManagedIdentityTable.column

      samsql"""insert into ${PetManagedIdentityTable.table} (${petManagedIdentityColumn.userId}, ${petManagedIdentityColumn.tenantId}, ${petManagedIdentityColumn.subscriptionId}, ${petManagedIdentityColumn.managedResourceGroupName}, ${petManagedIdentityColumn.objectId}, ${petManagedIdentityColumn.displayName})
           values (${petManagedIdentity.id.user}, ${petManagedIdentity.id.tenantId}, ${petManagedIdentity.id.subscriptionId}, ${petManagedIdentity.id.managedResourceGroupName}, ${petManagedIdentity.objectId}, ${petManagedIdentity.displayName})"""
        .update()
        .apply()
      petManagedIdentity
    }

  override def loadPetManagedIdentity(petManagedIdentityId: PetManagedIdentityId, samRequestContext: SamRequestContext): IO[Option[PetManagedIdentity]] =
    readOnlyTransaction("loadPetManagedIdentity", samRequestContext) { implicit session =>
      val petManagedIdentityTable = PetManagedIdentityTable.syntax

      val loadPetQuery = samsql"""select ${petManagedIdentityTable.resultAll}
       from ${PetManagedIdentityTable as petManagedIdentityTable}
       where ${petManagedIdentityTable.userId} = ${petManagedIdentityId.user} and ${petManagedIdentityTable.tenantId} = ${petManagedIdentityId.tenantId} and ${petManagedIdentityTable.subscriptionId} = ${petManagedIdentityId.subscriptionId} and ${petManagedIdentityTable.managedResourceGroupName} = ${petManagedIdentityId.managedResourceGroupName}"""

      val petRecordOpt = loadPetQuery.map(PetManagedIdentityTable(petManagedIdentityTable)).single().apply()
      petRecordOpt.map(unmarshalPetManagedIdentityRecord)
    }

  private def unmarshalPetManagedIdentityRecord(petRecord: PetManagedIdentityRecord): PetManagedIdentity =
    PetManagedIdentity(
      PetManagedIdentityId(petRecord.userId, petRecord.tenantId, petRecord.subscriptionId, petRecord.managedResourceGroupName),
      petRecord.objectId,
      petRecord.displayName
    )

  override def getUserFromPetManagedIdentity(petManagedIdentityObjectId: ManagedIdentityObjectId, samRequestContext: SamRequestContext): IO[Option[SamUser]] =
    readOnlyTransaction("getUserFromPetManagedIdentity", samRequestContext) { implicit session =>
      val petManagedIdentityTable = PetManagedIdentityTable.syntax
      val userTable = UserTable.syntax

      val loadUserQuery = samsql"""select ${userTable.resultAll}
                from ${UserTable as userTable}
                join ${PetManagedIdentityTable as petManagedIdentityTable} on ${petManagedIdentityTable.userId} = ${userTable.id}
                where ${petManagedIdentityTable.objectId} = ${petManagedIdentityObjectId}"""

      val userRecordOpt: Option[UserRecord] = loadUserQuery.map(UserTable(userTable)).single().apply()
      userRecordOpt.map(UserTable.unmarshalUserRecord)
    }

  override def createActionManagedIdentity(actionManagedIdentity: ActionManagedIdentity, samRequestContext: SamRequestContext): IO[ActionManagedIdentity] =
    serializableWriteTransaction("createActionManagedIdentity", samRequestContext) { implicit session =>
      val actionManagedIdentityColumn = ActionManagedIdentityTable.column
      val resourceTable = ResourceTable.syntax
      val resourceTypeTable = ResourceTypeTable.syntax
      val resourceActionTable = ResourceActionTable.syntax
      val managedResourceGroupTable = AzureManagedResourceGroupTable.syntax

      samsql"""insert into ${ActionManagedIdentityTable.table}
                 (
                   ${actionManagedIdentityColumn.resourceId},
                   ${actionManagedIdentityColumn.resourceActionId},
                   ${actionManagedIdentityColumn.managedResourceGroupId},
                   ${actionManagedIdentityColumn.objectId},
                   ${actionManagedIdentityColumn.displayName}
                 )
             values (
                      (select ${resourceTable.result.id} from ${ResourceTable as resourceTable} left join ${ResourceTypeTable as resourceTypeTable} on ${resourceTable.resourceTypeId} = ${resourceTypeTable.id} where ${resourceTable.name} = ${actionManagedIdentity.id.resourceId.resourceId} and ${resourceTypeTable.name} = ${actionManagedIdentity.id.resourceId.resourceTypeName}),
                      (select ${resourceActionTable.result.id} from ${ResourceActionTable as resourceActionTable} left join ${ResourceTypeTable as resourceTypeTable} on ${resourceActionTable.resourceTypeId} = ${resourceTypeTable.id} where ${resourceActionTable.action} = ${actionManagedIdentity.id.action} and ${resourceTypeTable.name} = ${actionManagedIdentity.id.resourceId.resourceTypeName}),
                      (select ${managedResourceGroupTable.result.id}
                      from ${AzureManagedResourceGroupTable as managedResourceGroupTable}
                      where ${managedResourceGroupTable.billingProfileId} = ${actionManagedIdentity.id.billingProfileId}),
                      ${actionManagedIdentity.objectId},
                      ${actionManagedIdentity.displayName}
                    )"""
        .update()
        .apply()
      actionManagedIdentity
    }

  type TableSyntax[A] = scalikejdbc.QuerySQLSyntaxProvider[scalikejdbc.SQLSyntaxSupport[A], A]

  override def loadActionManagedIdentity(
      actionManagedIdentityId: ActionManagedIdentityId,
      samRequestContext: SamRequestContext
  ): IO[Option[ActionManagedIdentity]] =
    readOnlyTransaction("loadActionManagedIdentity", samRequestContext) { implicit session =>
      implicit val actionManagedIdentityTable: TableSyntax[ActionManagedIdentityRecord] = ActionManagedIdentityTable.syntax
      implicit val managedResourceGroupTable: TableSyntax[AzureManagedResourceGroupRecord] = AzureManagedResourceGroupTable.syntax
      implicit val resourceActionTable: TableSyntax[ResourceActionRecord] = ResourceActionTable.syntax
      implicit val resourceTable: TableSyntax[ResourceRecord] = ResourceTable.syntax
      implicit val resourceTypeTable: TableSyntax[ResourceTypeRecord] = ResourceTypeTable.syntax

      val loadActionManagedIdentityQuery =
        samsql"""select ${resourceTable.result.name},
                 ${resourceTypeTable.result.name},
                 ${resourceActionTable.result.action},
                 ${managedResourceGroupTable.result.tenantId},
                 ${managedResourceGroupTable.result.subscriptionId},
                 ${managedResourceGroupTable.result.managedResourceGroupName},
                 ${managedResourceGroupTable.result.billingProfileId},
                 ${actionManagedIdentityTable.result.objectId},
                 ${actionManagedIdentityTable.result.displayName}
        from ${ActionManagedIdentityTable as actionManagedIdentityTable}
          left join ${AzureManagedResourceGroupTable as managedResourceGroupTable}
            on ${actionManagedIdentityTable.managedResourceGroupId} = ${managedResourceGroupTable.id}
          left join ${ResourceActionTable as resourceActionTable}
            on ${actionManagedIdentityTable.resourceActionId} = ${resourceActionTable.id}
          left join ${ResourceTable as resourceTable}
            on ${actionManagedIdentityTable.resourceId} = ${resourceTable.id}
          left join ${ResourceTypeTable as resourceTypeTable}
            on ${resourceTable.resourceTypeId} = ${resourceTypeTable.id}
        where ${resourceTable.name} = ${actionManagedIdentityId.resourceId.resourceId}
          and ${resourceTypeTable.name} = ${actionManagedIdentityId.resourceId.resourceTypeName}
          and ${managedResourceGroupTable.id} = ${actionManagedIdentityTable.managedResourceGroupId}
          and ${resourceActionTable.action} = ${actionManagedIdentityId.action}"""

      loadActionManagedIdentityQuery.map(unmarshalActionManagedIdentity).single().apply()
    }

  def loadActionManagedIdentity(
      resource: FullyQualifiedResourceId,
      action: ResourceAction,
      samRequestContext: SamRequestContext
  ): IO[Option[ActionManagedIdentity]] =
    readOnlyTransaction("loadActionManagedIdentityForResourceAction", samRequestContext) { implicit session =>
      implicit val actionManagedIdentityTable: TableSyntax[ActionManagedIdentityRecord] = ActionManagedIdentityTable.syntax
      implicit val managedResourceGroupTable: TableSyntax[AzureManagedResourceGroupRecord] = AzureManagedResourceGroupTable.syntax
      implicit val resourceActionTable: TableSyntax[ResourceActionRecord] = ResourceActionTable.syntax
      implicit val resourceTable: TableSyntax[ResourceRecord] = ResourceTable.syntax
      implicit val resourceTypeTable: TableSyntax[ResourceTypeRecord] = ResourceTypeTable.syntax

      val loadActionManagedIdentityQuery =
        samsql"""select ${resourceTable.result.name},
                 ${resourceTypeTable.result.name},
                 ${resourceActionTable.result.action},
                 ${managedResourceGroupTable.result.tenantId},
                 ${managedResourceGroupTable.result.subscriptionId},
                 ${managedResourceGroupTable.result.managedResourceGroupName},
                 ${managedResourceGroupTable.result.billingProfileId},
                 ${actionManagedIdentityTable.result.objectId},
                 ${actionManagedIdentityTable.result.displayName}
        from ${ActionManagedIdentityTable as actionManagedIdentityTable}
          left join ${AzureManagedResourceGroupTable as managedResourceGroupTable}
            on ${actionManagedIdentityTable.managedResourceGroupId} = ${managedResourceGroupTable.id}
          left join ${ResourceActionTable as resourceActionTable}
            on ${actionManagedIdentityTable.resourceActionId} = ${resourceActionTable.id}
          left join ${ResourceTable as resourceTable}
            on ${actionManagedIdentityTable.resourceId} = ${resourceTable.id}
          left join ${ResourceTypeTable as resourceTypeTable}
            on ${resourceTable.resourceTypeId} = ${resourceTypeTable.id}
        where ${resourceTable.name} = ${resource.resourceId}
          and ${resourceTypeTable.name} = ${resource.resourceTypeName}
          and ${resourceActionTable.action} = $action"""

      loadActionManagedIdentityQuery.map(unmarshalActionManagedIdentity).single().apply()
    }

  override def updateActionManagedIdentity(actionManagedIdentity: ActionManagedIdentity, samRequestContext: SamRequestContext): IO[ActionManagedIdentity] =
    serializableWriteTransaction("updateActionManagedIdentity", samRequestContext) { implicit session =>
      val actionManagedIdentityColumn = ActionManagedIdentityTable.column
      val resourceTable = ResourceTable.syntax
      val resourceTypeTable = ResourceTypeTable.syntax
      val resourceActionTable = ResourceActionTable.syntax
      val managedResourceGroupTable = AzureManagedResourceGroupTable.syntax

      val updateAmiQuery =
        samsql"""
                 update ${ActionManagedIdentityTable.table}
                 set
                   ${actionManagedIdentityColumn.objectId} = ${actionManagedIdentity.objectId},
                   ${actionManagedIdentityColumn.displayName} = ${actionManagedIdentity.displayName}
                 where
                   ${actionManagedIdentityColumn.resourceId} = (select ${resourceTable.result.id} from ${ResourceTable as resourceTable} left join ${ResourceTypeTable as resourceTypeTable} on ${resourceTable.resourceTypeId} = ${resourceTypeTable.id} where ${resourceTable.name} = ${actionManagedIdentity.id.resourceId.resourceId} and ${resourceTypeTable.name} = ${actionManagedIdentity.id.resourceId.resourceTypeName})
                   and ${actionManagedIdentityColumn.resourceActionId} = (select ${resourceActionTable.result.id}
                                                                        from ${ResourceActionTable as resourceActionTable}
                                                                          left join ${ResourceTypeTable as resourceTypeTable} on ${resourceActionTable.resourceTypeId} = ${resourceTypeTable.id}
                                                                        where ${resourceActionTable.action} = ${actionManagedIdentity.id.action}
                                                                          and ${resourceTypeTable.name} = ${actionManagedIdentity.id.resourceId.resourceTypeName})
                   and ${actionManagedIdentityColumn.managedResourceGroupId} = (select ${managedResourceGroupTable.result.id}
                                                                                from ${AzureManagedResourceGroupTable as managedResourceGroupTable}
                                                                                where ${managedResourceGroupTable.billingProfileId} = ${actionManagedIdentity.id.billingProfileId})
                   """
      val updated = updateAmiQuery.update().apply()
      if (updated != 1) {
        throw new WorkbenchException(s"Update cannot be applied because ${actionManagedIdentity.id} does not exist")
      }

      actionManagedIdentity
    }

  override def deleteActionManagedIdentity(actionManagedIdentityId: ActionManagedIdentityId, samRequestContext: SamRequestContext): IO[Unit] =
    serializableWriteTransaction("deleteActionManagedIdentity", samRequestContext) { implicit session =>
      val actionManagedIdentityTable = ActionManagedIdentityTable.syntax
      val resourceTable = ResourceTable.syntax
      val resourceTypeTable = ResourceTypeTable.syntax
      val resourceActionTable = ResourceActionTable.syntax
      val managedResourceGroupTable = AzureManagedResourceGroupTable.syntax

      val deleteActionManagedIdentityQuery =
        samsql"""delete from ${ActionManagedIdentityTable.table}
                  where ${actionManagedIdentityTable.resourceId} = (select ${resourceTable.result.id}
                                                                    from ${ResourceTable as resourceTable}
                                                                      left join ${ResourceTypeTable as resourceTypeTable} on ${resourceTable.resourceTypeId} = ${resourceTypeTable.id}
                                                                    where ${resourceTable.name} = ${actionManagedIdentityId.resourceId.resourceId}
                                                                      and ${resourceTypeTable.name} = ${actionManagedIdentityId.resourceId.resourceTypeName})
                  and ${actionManagedIdentityTable.managedResourceGroupId} = (select ${managedResourceGroupTable.result.id}
                                                                                        from ${AzureManagedResourceGroupTable as managedResourceGroupTable}
                                                                                        where ${managedResourceGroupTable.billingProfileId} = ${actionManagedIdentityId.billingProfileId})
                  and ${actionManagedIdentityTable.resourceActionId} = (select ${resourceActionTable.result.id}
                                                                        from ${ResourceActionTable as resourceActionTable}
                                                                          left join ${ResourceTypeTable as resourceTypeTable} on ${resourceActionTable.resourceTypeId} = ${resourceTypeTable.id}
                                                                        where ${resourceActionTable.action} = ${actionManagedIdentityId.action}
                                                                          and ${resourceTypeTable.name} = ${actionManagedIdentityId.resourceId.resourceTypeName})
      """
      if (deleteActionManagedIdentityQuery.update().apply() != 1) {
        throw new WorkbenchException(s"${actionManagedIdentityId} cannot be deleted because it already does not exist")
      }
    }

  override def getAllActionManagedIdentitiesForResource(
      resourceId: FullyQualifiedResourceId,
      samRequestContext: SamRequestContext
  ): IO[Seq[ActionManagedIdentity]] =
    readOnlyTransaction("loadActionManagedIdentitiesForResource", samRequestContext) { implicit session =>
      implicit val actionManagedIdentityTable: TableSyntax[ActionManagedIdentityRecord] = ActionManagedIdentityTable.syntax
      implicit val managedResourceGroupTable: TableSyntax[AzureManagedResourceGroupRecord] = AzureManagedResourceGroupTable.syntax
      implicit val resourceActionTable: TableSyntax[ResourceActionRecord] = ResourceActionTable.syntax
      implicit val resourceTable: TableSyntax[ResourceRecord] = ResourceTable.syntax
      implicit val resourceTypeTable: TableSyntax[ResourceTypeRecord] = ResourceTypeTable.syntax

      val listActionManagedIdentitysQuery =
        samsql"""select ${resourceTable.result.name}, ${resourceTypeTable.result.name}, ${resourceActionTable.result.action}, ${managedResourceGroupTable.result.tenantId}, ${managedResourceGroupTable.result.subscriptionId}, ${managedResourceGroupTable.result.managedResourceGroupName}, ${managedResourceGroupTable.result.billingProfileId}, ${actionManagedIdentityTable.result.objectId}, ${actionManagedIdentityTable.result.displayName}
        from ${ActionManagedIdentityTable as actionManagedIdentityTable}
          left join ${ResourceActionTable as resourceActionTable}
            on ${actionManagedIdentityTable.resourceActionId} = ${resourceActionTable.id}
          left join ${AzureManagedResourceGroupTable as managedResourceGroupTable}
            on ${actionManagedIdentityTable.managedResourceGroupId} = ${managedResourceGroupTable.id}
          left join ${ResourceTable as resourceTable}
            on ${actionManagedIdentityTable.resourceId} = ${resourceTable.id}
          left join ${ResourceTypeTable as resourceTypeTable}
            on ${resourceTable.resourceTypeId} = ${resourceTypeTable.id}
        where ${resourceTable.name} = ${resourceId.resourceId}
        and ${resourceTypeTable.name} = ${resourceId.resourceTypeName}
        """

      listActionManagedIdentitysQuery.map(unmarshalActionManagedIdentity).list().apply()
    }

  override def deleteAllActionManagedIdentitiesForResource(resourceId: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Unit] =
    serializableWriteTransaction("deleteAllActionManagedIdentitiesForResource", samRequestContext) { implicit session =>
      val actionManagedIdentityTable = ActionManagedIdentityTable.syntax
      val resourceTable = ResourceTable.syntax
      val resourceTypeTable = ResourceTypeTable.syntax
      val deleteActionManagedIdentityQuery =
        samsql"""delete from ${ActionManagedIdentityTable.table}
                 where ${actionManagedIdentityTable.resourceId} = (select ${resourceTable.result.id} from ${ResourceTable as resourceTable} left join ${ResourceTypeTable as resourceTypeTable} on ${resourceTable.resourceTypeId} = ${resourceTypeTable.id} where ${resourceTable.name} = ${resourceId.resourceId} and ${resourceTypeTable.name} = ${resourceId.resourceTypeName})"""
      deleteActionManagedIdentityQuery.update().apply()
    }

  override def getAllActionManagedIdentitiesForBillingProfile(
      billingProfileId: BillingProfileId,
      samRequestContext: SamRequestContext
  ): IO[Seq[ActionManagedIdentity]] =
    readOnlyTransaction("loadActionManagedIdentitiesForResource", samRequestContext) { implicit session =>
      implicit val actionManagedIdentityTable: TableSyntax[ActionManagedIdentityRecord] = ActionManagedIdentityTable.syntax
      implicit val managedResourceGroupTable: TableSyntax[AzureManagedResourceGroupRecord] = AzureManagedResourceGroupTable.syntax
      implicit val resourceActionTable: TableSyntax[ResourceActionRecord] = ResourceActionTable.syntax
      implicit val resourceTable: TableSyntax[ResourceRecord] = ResourceTable.syntax
      implicit val resourceTypeTable: TableSyntax[ResourceTypeRecord] = ResourceTypeTable.syntax

      val listActionManagedIdentitysQuery =
        samsql"""select ${resourceTable.result.name}, ${resourceTypeTable.result.name}, ${resourceActionTable.result.action}, ${managedResourceGroupTable.result.tenantId}, ${managedResourceGroupTable.result.subscriptionId}, ${managedResourceGroupTable.result.managedResourceGroupName}, ${managedResourceGroupTable.result.billingProfileId}, ${actionManagedIdentityTable.result.objectId}, ${actionManagedIdentityTable.result.displayName}
        from ${ActionManagedIdentityTable as actionManagedIdentityTable}
          left join ${ResourceActionTable as resourceActionTable}
            on ${actionManagedIdentityTable.resourceActionId} = ${resourceActionTable.id}
          left join ${AzureManagedResourceGroupTable as managedResourceGroupTable}
            on ${actionManagedIdentityTable.managedResourceGroupId} = ${managedResourceGroupTable.id}
          left join ${ResourceTable as resourceTable}
            on ${actionManagedIdentityTable.resourceId} = ${resourceTable.id}
          left join ${ResourceTypeTable as resourceTypeTable}
            on ${resourceTable.resourceTypeId} = ${resourceTypeTable.id}
        where ${managedResourceGroupTable.billingProfileId} = $billingProfileId
        """

      listActionManagedIdentitysQuery.map(unmarshalActionManagedIdentity).list().apply()
    }
  override def deleteAllActionManagedIdentitiesForBillingProfile(billingProfileId: BillingProfileId, samRequestContext: SamRequestContext): IO[Unit] =
    serializableWriteTransaction("deleteAllActionManagedIdentitiesForManagedResourceGroup", samRequestContext) { implicit session =>
      val actionManagedIdentityTable = ActionManagedIdentityTable.syntax
      val managedResourceGroupTable = AzureManagedResourceGroupTable.syntax
      val deleteActionManagedIdentityQuery =
        samsql"""delete from ${ActionManagedIdentityTable.table}
                 where ${actionManagedIdentityTable.managedResourceGroupId} = (select ${managedResourceGroupTable.result.id}
                                                                        from ${AzureManagedResourceGroupTable as managedResourceGroupTable}
                                                                        where ${managedResourceGroupTable.billingProfileId} = $billingProfileId)
             """
      deleteActionManagedIdentityQuery.update().apply()
    }

  private def unmarshalActionManagedIdentity(rs: WrappedResultSet)(implicit
      resourceTable: TableSyntax[ResourceRecord],
      resourceTypeTable: TableSyntax[ResourceTypeRecord],
      resourceActionTable: TableSyntax[ResourceActionRecord],
      actionManagedIdentityTable: TableSyntax[ActionManagedIdentityRecord],
      managedResourceGroupTable: TableSyntax[AzureManagedResourceGroupRecord]
  ) =
    ActionManagedIdentity(
      ActionManagedIdentityId(
        FullyQualifiedResourceId(rs.get[ResourceTypeName](resourceTypeTable.resultName.name), rs.get[ResourceId](resourceTable.resultName.name)),
        rs.get[ResourceAction](resourceActionTable.resultName.action),
        rs.get[BillingProfileId](managedResourceGroupTable.resultName.billingProfileId)
      ),
      rs.get[ManagedIdentityObjectId](actionManagedIdentityTable.resultName.objectId),
      rs.get[ManagedIdentityDisplayName](actionManagedIdentityTable.resultName.displayName),
      ManagedResourceGroupCoordinates(
        rs.get[TenantId](managedResourceGroupTable.resultName.tenantId),
        rs.get[SubscriptionId](managedResourceGroupTable.resultName.subscriptionId),
        rs.get[ManagedResourceGroupName](managedResourceGroupTable.resultName.managedResourceGroupName)
      )
    )

  override def setUserRegisteredAt(userId: WorkbenchUserId, registeredAt: Instant, samRequestContext: SamRequestContext): IO[Unit] =
    serializableWriteTransaction("setUserRegisteredAt", samRequestContext) { implicit session =>
      val u = UserTable.column
      val results =
        samsql"""update ${UserTable.table}
               set (${u.registeredAt}, ${u.updatedAt}) =
               (
                 $registeredAt,
                 ${Instant.now()}
               )
               where ${u.id} = $userId and ${u.registeredAt} is null"""
          .update()
          .apply()

      if (results != 1) {
        throw new WorkbenchException(
          s"Cannot update registeredAt for user ${userId} because user does not exist or the registeredAt date has already been set for this user"
        )
      } else {
        ()
      }
    }

  override def getUserAttributes(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[SamUserAttributes]] =
    readOnlyTransaction("getUserAttributes", samRequestContext) { implicit session =>
      val userAttributesTable = UserAttributesTable.syntax
      val column = UserAttributesTable.column

      val loadUserAttributesQuery =
        samsql"""
                 select ${userAttributesTable.resultAll}
                 from ${UserAttributesTable as userAttributesTable}
                 where ${column.samUserId} = $userId
        """

      val userAttributesRecordOpt: Option[UserAttributesRecord] = loadUserAttributesQuery.map(UserAttributesTable(userAttributesTable)).first().apply()
      userAttributesRecordOpt.map(UserAttributesTable.unmarshalUserAttributesRecord)
    }

  override def setUserAttributes(userAttributes: SamUserAttributes, samRequestContext: SamRequestContext): IO[Unit] =
    serializableWriteTransaction("setUserAttributes", samRequestContext) { implicit session =>
      val userAttributesTable = UserAttributesTable.syntax
      val userAttributesColumns = UserAttributesTable.column
      samsql"""
        insert into ${UserAttributesTable as userAttributesTable} (${userAttributesColumns.samUserId}, ${userAttributesColumns.marketingConsent}, ${userAttributesColumns.updatedAt})
          values (${userAttributes.userId}, ${userAttributes.marketingConsent}, ${Instant.now()})
        on conflict(${userAttributesColumns.samUserId})
          do update set ${userAttributesColumns.marketingConsent} = ${userAttributes.marketingConsent},
            ${userAttributesColumns.updatedAt} = ${Instant.now()}
           """.update().apply() > 0
    }

  override def listParentGroups(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Set[WorkbenchGroupName]] =
    readOnlyTransaction("listParentGroups", samRequestContext) { implicit session =>
      val group = GroupTable.syntax("g")
      val parent = GroupTable.syntax("pg")
      val groupMember = GroupMemberTable.syntax("gm")

      val loadParentGroupsQuery =
        samsql"""select ${parent.result.name}
                 from ${GroupTable as group}
                 join ${GroupMemberTable as groupMember} on ${group.id} = ${groupMember.memberGroupId}
                 join ${GroupTable as parent} on ${parent.id} = ${groupMember.groupId}
                 where ${group.name} = $groupName"""

      loadParentGroupsQuery.map(rs => WorkbenchGroupName(rs.string(parent.resultName.name))).list().apply().toSet
    }
}
