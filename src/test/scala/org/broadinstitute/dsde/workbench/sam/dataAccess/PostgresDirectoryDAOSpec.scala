package org.broadinstitute.dsde.workbench.sam.dataAccess

import akka.http.scaladsl.model.StatusCodes
import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccount, ServiceAccountDisplayName, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.sam.Generator.genWorkbenchUserBoth
import org.broadinstitute.dsde.workbench.sam.TestSupport.{databaseEnabled, databaseEnabledClue, samRequestContext, tosConfig}
import org.broadinstitute.dsde.workbench.sam.azure._
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory._
import org.broadinstitute.dsde.workbench.sam.db.TestDbReference
import org.broadinstitute.dsde.workbench.sam.db.tables.TosTable
import org.broadinstitute.dsde.workbench.sam.matchers.TimeMatchers
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.model.api.{AdminUpdateUserRequest, SamUser, SamUserAttributes}
import org.broadinstitute.dsde.workbench.sam.{Generator, RetryableAnyFreeSpec, TestSupport}
import org.scalatest.Inside.inside
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterEach, OptionValues}

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.{Date, UUID}
import scala.concurrent.duration._

class PostgresDirectoryDAOSpec extends RetryableAnyFreeSpec with Matchers with BeforeAndAfterEach with TimeMatchers with OptionValues {
  val dao = new PostgresDirectoryDAO(TestSupport.dbRef, TestSupport.dbRef)
  val policyDAO = new PostgresAccessPolicyDAO(TestSupport.dbRef, TestSupport.dbRef)
  val azureManagedResourceGroupDAO = new PostgresAzureManagedResourceGroupDAO(TestSupport.dbRef, TestSupport.dbRef)

  val defaultGroupName: WorkbenchGroupName = WorkbenchGroupName("group")
  val defaultGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(defaultGroupName, Set.empty, WorkbenchEmail("foo@bar.com"))
  val defaultUser: SamUser = Generator.genWorkbenchUserBoth.sample.get
  val defaultPetSA: PetServiceAccount = PetServiceAccount(
    PetServiceAccountId(defaultUser.id, GoogleProject("testProject")),
    ServiceAccount(ServiceAccountSubjectId("testGoogleSubjectId"), WorkbenchEmail("test@pet.co"), ServiceAccountDisplayName("whoCares"))
  )
  val defaultPetMI: PetManagedIdentity = PetManagedIdentity(
    PetManagedIdentityId(defaultUser.id, TenantId("testTenant"), SubscriptionId("testSubscription"), ManagedResourceGroupName("testMrg")),
    ManagedIdentityObjectId("testObjectId"),
    ManagedIdentityDisplayName("Managed Identity")
  )

  val actionPatterns: Set[ResourceActionPattern] = Set(
    ResourceActionPattern("write", "description of pattern1", authDomainConstrainable = false),
    ResourceActionPattern("read", "description of pattern2", authDomainConstrainable = false)
  )
  val writeAction: ResourceAction = ResourceAction("write")
  val readAction: ResourceAction = ResourceAction("read")

  val ownerRoleName: ResourceRoleName = ResourceRoleName("role1")
  val ownerRole: ResourceRole = ResourceRole(ownerRoleName, Set(writeAction, readAction))
  val readerRole: ResourceRole = ResourceRole(ResourceRoleName("role2"), Set(readAction))
  val actionlessRole: ResourceRole = ResourceRole(ResourceRoleName("cantDoNuthin"), Set()) // yeah, it's a double negative, sue me!
  val roles: Set[ResourceRole] = Set(ownerRole, readerRole, actionlessRole)

  val resourceTypeName: ResourceTypeName = ResourceTypeName("awesomeType")
  val resourceType: ResourceType = ResourceType(resourceTypeName, actionPatterns, roles, ownerRoleName)
  val defaultResource: Resource = Resource(resourceType.name, ResourceId("defaultResource"), Set.empty)
  val defaultPolicy: AccessPolicy = AccessPolicy(
    FullyQualifiedPolicyId(defaultResource.fullyQualifiedId, AccessPolicyName("defaultPolicy")),
    Set.empty,
    WorkbenchEmail("default@policy.com"),
    roles.map(_.roleName),
    Set(writeAction, readAction),
    Set.empty,
    public = false
  )

  val defaultTenantId = TenantId("testTenant")
  val defaultSubscriptionId = SubscriptionId(UUID.randomUUID().toString)
  val defaultManagedResourceGroupName = ManagedResourceGroupName("mrg-test")
  val defaultManagedResourceGroupCoordinates = ManagedResourceGroupCoordinates(defaultTenantId, defaultSubscriptionId, defaultManagedResourceGroupName)
  val defaultBillingProfileId = BillingProfileId(UUID.randomUUID().toString)
  val defaultBillingProfileResource = defaultResource.copy(resourceId = defaultBillingProfileId.asResourceId)
  val defaultManagedResourceGroup = ManagedResourceGroup(defaultManagedResourceGroupCoordinates, defaultBillingProfileId)

  val defaultActionManagedIdentities: Set[ActionManagedIdentity] = Set(readAction, writeAction).map(action =>
    ActionManagedIdentity(
      ActionManagedIdentityId(
        FullyQualifiedResourceId(defaultResource.resourceTypeName, defaultResource.resourceId),
        action,
        defaultBillingProfileId
      ),
      ManagedIdentityObjectId(UUID.randomUUID().toString),
      ManagedIdentityDisplayName(s"whoCares-$action"),
      defaultManagedResourceGroupCoordinates
    )
  )

  override protected def beforeEach(): Unit =
    TestSupport.truncateAll

  private def emptyWorkbenchGroup(groupName: String): BasicWorkbenchGroup =
    BasicWorkbenchGroup(WorkbenchGroupName(groupName), Set.empty, WorkbenchEmail(s"$groupName@test.com"))

  "PostgresDirectoryDAO" - {
    "createGroup" - {
      "create a group" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync() shouldEqual defaultGroup
      }

      "create a group with access instructions" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createGroup(defaultGroup, Option("access instructions"), samRequestContext = samRequestContext).unsafeRunSync() shouldEqual defaultGroup
      }

      "not allow groups with duplicate names" in {
        assume(databaseEnabled, databaseEnabledClue)
        val duplicateGroup = BasicWorkbenchGroup(defaultGroupName, Set.empty, WorkbenchEmail("foo@bar.com"))
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        val exception = intercept[WorkbenchExceptionWithErrorReport] {
          dao.createGroup(duplicateGroup, samRequestContext = samRequestContext).unsafeRunSync()
        }

        exception.errorReport.statusCode shouldEqual Some(StatusCodes.Conflict)
      }

      "create groups with subGroup members" in {
        assume(databaseEnabled, databaseEnabledClue)
        val subGroup1 = defaultGroup
        val subGroup2 = BasicWorkbenchGroup(WorkbenchGroupName("subGroup2"), Set.empty, WorkbenchEmail("bar@baz.com"))
        val members: Set[WorkbenchSubject] = Set(subGroup1.id, subGroup2.id)
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), members, WorkbenchEmail("baz@qux.com"))

        dao.createGroup(subGroup1, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(subGroup2, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        val loadedGroup = dao.loadGroup(parentGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"Failed to load group ${parentGroup.id}"))
        loadedGroup.members shouldEqual members
      }

      "create groups with policy members" in {
        assume(databaseEnabled, databaseEnabledClue)
        val memberPolicy = defaultPolicy
        val members: Set[WorkbenchSubject] = Set(memberPolicy.id)
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), members, WorkbenchEmail("baz@qux.com"))

        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()
        dao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        val loadedGroup = dao.loadGroup(parentGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"Failed to load group ${parentGroup.id}"))
        loadedGroup.members shouldEqual members
      }

      "create groups with both subGroup and policy members" in {
        assume(databaseEnabled, databaseEnabledClue)
        val subGroup = defaultGroup
        dao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()

        val memberPolicy = defaultPolicy
        val members: Set[WorkbenchSubject] = Set(memberPolicy.id, subGroup.id)
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), members, WorkbenchEmail("baz@qux.com"))

        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()
        dao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        val loadedGroup = dao.loadGroup(parentGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"Failed to load group ${parentGroup.id}"))
        loadedGroup.members shouldEqual members
      }

      "not allow nonexistent group members" in {
        assume(databaseEnabled, databaseEnabledClue)
        val subGroup1 = defaultGroup
        val subGroup2 = BasicWorkbenchGroup(WorkbenchGroupName("subGroup2"), Set.empty, WorkbenchEmail("bar@baz.com"))
        val members: Set[WorkbenchSubject] = Set(subGroup1.id, subGroup2.id)
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), members, WorkbenchEmail("baz@qux.com"))

        assertThrows[WorkbenchException] {
          dao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()
        }
      }
    }

    "loadGroup" - {
      "load a group" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        val loadedGroup = dao.loadGroup(defaultGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"Failed to load group $defaultGroupName"))
        loadedGroup shouldEqual defaultGroup
      }

      "return None when loading a nonexistent group" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.loadGroup(WorkbenchGroupName("fakeGroup"), samRequestContext).unsafeRunSync() shouldBe None
      }
    }

    "loadGroupEmail" - {
      "load a group's email" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        val loadedEmail = dao.loadGroupEmail(defaultGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"Failed to load group ${defaultGroup.id}"))
        loadedEmail shouldEqual defaultGroup.email
      }

      "return None when trying to load the email for a nonexistent group" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.loadGroupEmail(WorkbenchGroupName("fakeGroup"), samRequestContext).unsafeRunSync() shouldBe None
      }
    }

    "deleteGroup" - {
      "delete groups" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()

        val loadedGroup = dao.loadGroup(defaultGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"Failed to load group $defaultGroupName"))
        loadedGroup shouldEqual defaultGroup

        dao.deleteGroup(defaultGroup.id, samRequestContext).unsafeRunSync()

        dao.loadGroup(defaultGroup.id, samRequestContext).unsafeRunSync() shouldBe None
      }

      "not delete a group that is still a member of another group" in {
        assume(databaseEnabled, databaseEnabledClue)
        val subGroup = defaultGroup.copy(id = WorkbenchGroupName("subGroup"))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), Set(subGroup.id), WorkbenchEmail("bar@baz.com"))

        dao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        val inUseException = intercept[WorkbenchExceptionWithErrorReport] {
          dao.deleteGroup(subGroup.id, samRequestContext).unsafeRunSync()
        }

        inUseException.errorReport.statusCode shouldEqual Some(StatusCodes.Conflict)

        dao.loadGroup(subGroup.id, samRequestContext).unsafeRunSync() shouldEqual Option(subGroup)
      }
    }

    "addGroupMember" - {
      "add groups to other groups" in {
        assume(databaseEnabled, databaseEnabledClue)
        val subGroup = emptyWorkbenchGroup("subGroup")
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.addGroupMember(defaultGroup.id, subGroup.id, samRequestContext).unsafeRunSync() shouldBe true

        val loadedGroup = dao.loadGroup(defaultGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"failed to load group ${defaultGroup.id}"))
        loadedGroup.members should contain theSameElementsAs Set(subGroup.id)

        loadedGroup.version shouldEqual 2
      }

      "add users to groups" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        dao.addGroupMember(defaultGroup.id, defaultUser.id, samRequestContext).unsafeRunSync() shouldBe true

        val loadedGroup = dao.loadGroup(defaultGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"failed to load group ${defaultGroup.id}"))
        loadedGroup.members should contain theSameElementsAs Set(defaultUser.id)

        loadedGroup.version shouldEqual 2
      }

      "add policies to groups" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()

        dao.addGroupMember(defaultGroup.id, defaultPolicy.id, samRequestContext).unsafeRunSync() shouldBe true

        val loadedGroup = dao.loadGroup(defaultGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"failed to load group ${defaultGroup.id}"))
        loadedGroup.members should contain theSameElementsAs Set(defaultPolicy.id)

        loadedGroup.version shouldEqual 2
      }

      "add groups to policies" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()

        dao.addGroupMember(defaultPolicy.id, defaultGroup.id, samRequestContext).unsafeRunSync()

        val loadedPolicy =
          policyDAO.loadPolicy(defaultPolicy.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"s'failed to load policy ${defaultPolicy.id}"))
        loadedPolicy.members should contain theSameElementsAs Set(defaultGroup.id)

        loadedPolicy.version shouldEqual 2
      }

      "add users to policies" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()

        dao.addGroupMember(defaultPolicy.id, defaultUser.id, samRequestContext).unsafeRunSync()

        val loadedPolicy =
          policyDAO.loadPolicy(defaultPolicy.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"s'failed to load policy ${defaultPolicy.id}"))
        loadedPolicy.members should contain theSameElementsAs Set(defaultUser.id)

        loadedPolicy.version shouldEqual 2
      }

      "add policies to other policies" in {
        assume(databaseEnabled, databaseEnabledClue)
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()
        val memberPolicy =
          defaultPolicy.copy(id = defaultPolicy.id.copy(accessPolicyName = AccessPolicyName("memberPolicy")), email = WorkbenchEmail("copied@policy.com"))
        policyDAO.createPolicy(memberPolicy, samRequestContext).unsafeRunSync()

        dao.addGroupMember(defaultPolicy.id, memberPolicy.id, samRequestContext).unsafeRunSync()

        val loadedPolicy =
          policyDAO.loadPolicy(defaultPolicy.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"s'failed to load policy ${defaultPolicy.id}"))
        loadedPolicy.members should contain theSameElementsAs Set(memberPolicy.id)

        loadedPolicy.version shouldEqual 2
      }

      "trying to add a group that does not exist will fail" in {
        assume(databaseEnabled, databaseEnabledClue)
        val subGroup = emptyWorkbenchGroup("subGroup")
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()

        assertThrows[WorkbenchException] {
          dao.addGroupMember(defaultGroup.id, subGroup.id, samRequestContext).unsafeRunSync() shouldBe true
        }
      }

      "prevents group cycles" in {
        assume(databaseEnabled, databaseEnabledClue)
        val subGroup = emptyWorkbenchGroup("subGroup")
        val badGroup = emptyWorkbenchGroup("badGroup")
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(badGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.addGroupMember(defaultGroup.id, subGroup.id, samRequestContext).unsafeRunSync() shouldBe true
        dao.addGroupMember(subGroup.id, badGroup.id, samRequestContext).unsafeRunSync() shouldBe true

        val exception = intercept[WorkbenchExceptionWithErrorReport] {
          dao.addGroupMember(badGroup.id, defaultGroup.id, samRequestContext).unsafeRunSync()
        }

        exception.errorReport.statusCode shouldBe Some(StatusCodes.BadRequest)
        exception.errorReport.message should include(defaultGroup.email.value)
      }
    }

    "batchLoadGroupEmail" - {
      "batch load multiple group emails" in {
        assume(databaseEnabled, databaseEnabledClue)
        val group1 = emptyWorkbenchGroup("group1")
        val group2 = emptyWorkbenchGroup("group2")

        dao.createGroup(group1, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(group2, samRequestContext = samRequestContext).unsafeRunSync()

        dao.batchLoadGroupEmail(Set(group1.id, group2.id), samRequestContext).unsafeRunSync() should contain theSameElementsAs Set(group1, group2).map(group =>
          (group.id, group.email)
        )
      }
    }

    "removeGroupMember" - {
      "remove groups from other groups" in {
        assume(databaseEnabled, databaseEnabledClue)
        val subGroup = emptyWorkbenchGroup("subGroup")
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.addGroupMember(defaultGroup.id, subGroup.id, samRequestContext).unsafeRunSync() shouldBe true
        val afterAdd = dao.loadGroup(defaultGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"failed to load group ${defaultGroup.id}"))
        afterAdd.members should contain theSameElementsAs Set(subGroup.id)
        afterAdd.version shouldEqual 2

        dao.removeGroupMember(defaultGroup.id, subGroup.id, samRequestContext).unsafeRunSync() shouldBe true

        val afterRemove = dao.loadGroup(defaultGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"failed to load group ${defaultGroup.id}"))
        afterRemove.members shouldBe empty
        afterRemove.version shouldEqual 3
      }

      "remove users from groups" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        dao.addGroupMember(defaultGroup.id, defaultUser.id, samRequestContext).unsafeRunSync() shouldBe true
        val afterAdd = dao.loadGroup(defaultGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"failed to load group ${defaultGroup.id}"))
        afterAdd.members should contain theSameElementsAs Set(defaultUser.id)
        afterAdd.version shouldEqual 2

        dao.removeGroupMember(defaultGroup.id, defaultUser.id, samRequestContext).unsafeRunSync() shouldBe true

        val afterRemove = dao.loadGroup(defaultGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"failed to load group ${defaultGroup.id}"))
        afterRemove.members shouldBe empty
        afterRemove.version shouldEqual 3
      }

      "remove policies from groups" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()

        dao.addGroupMember(defaultGroup.id, defaultPolicy.id, samRequestContext).unsafeRunSync() shouldBe true
        val afterAdd = dao.loadGroup(defaultGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"failed to load group ${defaultGroup.id}"))
        afterAdd.members should contain theSameElementsAs Set(defaultPolicy.id)
        afterAdd.version shouldEqual 2

        dao.removeGroupMember(defaultGroup.id, defaultPolicy.id, samRequestContext).unsafeRunSync() shouldBe true

        val afterRemove = dao.loadGroup(defaultGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"failed to load group ${defaultGroup.id}"))
        afterRemove.members shouldBe empty
        afterRemove.version shouldEqual 3
      }

      "remove groups from policies" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.createGroup(defaultGroup.copy(members = Set(defaultUser.id)), samRequestContext = samRequestContext).unsafeRunSync()
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()

        dao.addGroupMember(defaultPolicy.id, defaultGroup.id, samRequestContext).unsafeRunSync()
        val afterAdd = policyDAO.loadPolicy(defaultPolicy.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"s'failed to load policy ${defaultPolicy.id}"))
        afterAdd.members should contain theSameElementsAs Set(defaultGroup.id)
        afterAdd.version shouldEqual 2

        policyDAO.listFlattenedPolicyMembers(defaultPolicy.id, samRequestContext).unsafeRunSync() should contain theSameElementsAs Set(defaultUser)
        dao.removeGroupMember(defaultPolicy.id, defaultGroup.id, samRequestContext).unsafeRunSync()

        val afterRemove =
          policyDAO.loadPolicy(defaultPolicy.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"s'failed to load policy ${defaultPolicy.id}"))
        afterRemove.members shouldBe empty
        afterRemove.version shouldEqual 3

        policyDAO.listFlattenedPolicyMembers(defaultPolicy.id, samRequestContext).unsafeRunSync() shouldBe empty
      }

      "remove users from policies" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()

        dao.addGroupMember(defaultPolicy.id, defaultUser.id, samRequestContext).unsafeRunSync()
        dao.removeGroupMember(defaultPolicy.id, defaultUser.id, samRequestContext).unsafeRunSync()

        val loadedPolicy =
          policyDAO.loadPolicy(defaultPolicy.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"s'failed to load policy ${defaultPolicy.id}"))
        loadedPolicy.members shouldBe empty
        loadedPolicy.version shouldBe 3
      }

      "remove policies from other policies" in {
        assume(databaseEnabled, databaseEnabledClue)
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()
        val memberPolicy =
          defaultPolicy.copy(id = defaultPolicy.id.copy(accessPolicyName = AccessPolicyName("memberPolicy")), email = WorkbenchEmail("copied@policy.com"))
        policyDAO.createPolicy(memberPolicy, samRequestContext).unsafeRunSync()

        dao.addGroupMember(defaultPolicy.id, memberPolicy.id, samRequestContext).unsafeRunSync()
        dao.removeGroupMember(defaultPolicy.id, memberPolicy.id, samRequestContext).unsafeRunSync()

        val loadedPolicy =
          policyDAO.loadPolicy(defaultPolicy.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"s'failed to load policy ${defaultPolicy.id}"))
        loadedPolicy.members shouldBe empty
        loadedPolicy.version shouldBe 3
      }
    }

    "createUser" - {
      "returns the same user" in {
        assume(databaseEnabled, databaseEnabledClue)
        // Arrange
        val expectedInstant = Instant.parse("2007-07-07T07:07:07Z")
        val expectedUser = defaultUser.copy(registeredAt = Some(expectedInstant))

        // Act
        val createdUser = dao.createUser(expectedUser, samRequestContext).unsafeRunSync()

        // Assert
        inside(createdUser) { user =>
          user.id should equal(expectedUser.id)
          user.googleSubjectId should equal(expectedUser.googleSubjectId)
          user.email should equal(expectedUser.email)
          user.azureB2CId should equal(expectedUser.azureB2CId)
          user.enabled should equal(expectedUser.enabled)
          user.registeredAt should equal(expectedUser.registeredAt)
        }
      }

      "returns the samUser with the createdAt datetime set to the current time if one is not specified" in {
        assume(databaseEnabled, databaseEnabledClue)
        // Arrange
        val expectedUser = defaultUser

        // Act
        val createdUser = dao.createUser(expectedUser, samRequestContext).unsafeRunSync()

        // Assert
        inside(createdUser) { user =>
          user.createdAt should beAround(Instant.now())
        }
      }

      "returns the samUser with the createdAt datetime set to the specified instant" in {
        assume(databaseEnabled, databaseEnabledClue)
        val expectedInstant = Instant.parse("2000-01-02T03:04:05Z")
        // Arrange
        val expectedUser = defaultUser.copy(createdAt = expectedInstant)

        // Act
        val createdUser = dao.createUser(expectedUser, samRequestContext).unsafeRunSync()

        // Assert
        inside(createdUser) { user =>
          user.createdAt shouldBe expectedInstant
        }
      }

      "returns the samUser with the updatedAt datetime set to the current time if one is not specified" in {
        assume(databaseEnabled, databaseEnabledClue)
        // Arrange
        val expectedUser = defaultUser

        // Act
        val createdUser = dao.createUser(expectedUser, samRequestContext).unsafeRunSync()

        // Assert
        inside(createdUser) { user =>
          user.updatedAt should beAround(Instant.now())
        }
      }

      "returns the samUser with the updatedAt datetime set to the specified instant" in {
        assume(databaseEnabled, databaseEnabledClue)
        val expectedInstant = Instant.parse("2000-01-02T03:04:05Z")
        // Arrange
        val expectedUser = defaultUser.copy(updatedAt = expectedInstant)

        // Act
        val createdUser = dao.createUser(expectedUser, samRequestContext).unsafeRunSync()

        // Assert
        inside(createdUser) { user =>
          user.updatedAt shouldBe expectedInstant
        }
      }
    }

    "loadUser" - {
      "loads a persisted user" in {
        assume(databaseEnabled, databaseEnabledClue)
        // Arrange
        val instant = Instant.parse("2007-07-07T07:07:07Z")
        val expectedUser = defaultUser.copy(
          createdAt = instant,
          registeredAt = Some(instant),
          updatedAt = instant
        )

        // Act
        val createdUser = dao.createUser(expectedUser, samRequestContext).unsafeRunSync()

        // Assert
        inside(createdUser) { user =>
          user.id should equal(expectedUser.id)
          user.googleSubjectId should equal(expectedUser.googleSubjectId)
          user.email should equal(expectedUser.email)
          user.azureB2CId should equal(expectedUser.azureB2CId)
          user.enabled should equal(expectedUser.enabled)
          user.createdAt should equal(expectedUser.createdAt)
          user.registeredAt should equal(expectedUser.registeredAt)
          user.updatedAt should equal(expectedUser.updatedAt)
        }
      }

      "loads a user without a google subject id" in {
        assume(databaseEnabled, databaseEnabledClue)
        // Arrange
        val user = Generator.genWorkbenchUserAzure.sample.get
        assume(user.googleSubjectId.isEmpty)
        dao.createUser(user, samRequestContext).unsafeRunSync()

        // Act
        val loadedUser = dao.loadUser(user.id, samRequestContext).unsafeRunSync()

        // Assert
        loadedUser.value should have(Symbol("googleSubjectId")(None))
      }

      "loads a user without an AzureB2C id" in {
        assume(databaseEnabled, databaseEnabledClue)
        // Arrange
        val user = Generator.genWorkbenchUserGoogle.sample.get
        assume(user.azureB2CId.isEmpty)
        dao.createUser(user, samRequestContext).unsafeRunSync()

        // Act
        val loadedUser = dao.loadUser(user.id, samRequestContext).unsafeRunSync()

        // Assert
        loadedUser.value should have(Symbol("azureB2CId")(None))
      }
    }

    "batchLoadUsers" - {
      "loads a list of users" in {
        assume(databaseEnabled, databaseEnabledClue)
        val users = Seq.range(0, 10).map(_ => Generator.genWorkbenchUserBoth.sample.get)
        users.foreach(user => dao.createUser(user, samRequestContext).unsafeRunSync())
        val loadedUsers = dao.batchLoadUsers(users.map(_.id).toSet, samRequestContext).unsafeRunSync()
        loadedUsers should contain theSameElementsAs users
      }
    }

    "deleteUser" - {
      "delete users" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync() shouldEqual defaultUser
        val loadedUser = dao.loadUser(defaultUser.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"failed to load user ${defaultUser.id}"))
        loadedUser shouldEqual defaultUser
        dao.deleteUser(defaultUser.id, samRequestContext).unsafeRunSync()
        dao.loadUser(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe None
      }

      "delete a user that is still a member of a group" in {
        assume(databaseEnabled, databaseEnabledClue)
        val user = defaultUser
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), Set(user.id), WorkbenchEmail("bar@baz.com"))

        dao.createUser(user, samRequestContext).unsafeRunSync()
        dao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.deleteUser(user.id, samRequestContext).unsafeRunSync()
        dao.loadUser(user.id, samRequestContext).unsafeRunSync() shouldEqual None
      }
    }

    "listUsersGroups" - {
      "list all of the groups a user is in" in {
        assume(databaseEnabled, databaseEnabledClue)
        val subGroupId = WorkbenchGroupName("subGroup")
        val subGroup = BasicWorkbenchGroup(subGroupId, Set(defaultUser.id), WorkbenchEmail("subGroup@foo.com"))
        val parentGroupId = WorkbenchGroupName("parentGroup")
        val parentGroup = BasicWorkbenchGroup(parentGroupId, Set(defaultUser.id, subGroupId), WorkbenchEmail("parentGroup@foo.com"))

        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        val usersGroups = dao.listUsersGroups(defaultUser.id, samRequestContext).unsafeRunSync()
        usersGroups should contain theSameElementsAs Set(subGroupId, parentGroupId)
      }

      "list all of the policies a user is in" in {
        assume(databaseEnabled, databaseEnabledClue)
        val subPolicy = defaultPolicy.copy(
          id = defaultPolicy.id.copy(accessPolicyName = AccessPolicyName("sp")),
          email = WorkbenchEmail("sp@policy.com"),
          members = Set(defaultUser.id)
        )
        val parentPolicy = defaultPolicy.copy(
          id = defaultPolicy.id.copy(accessPolicyName = AccessPolicyName("pp")),
          email = WorkbenchEmail("pp@policy.com"),
          members = Set(subPolicy.id)
        )

        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(subPolicy, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(parentPolicy, samRequestContext).unsafeRunSync()

        dao.listUsersGroups(defaultUser.id, samRequestContext).unsafeRunSync() should contain theSameElementsAs Set(subPolicy.id, parentPolicy.id)
      }
    }

    "createPetServiceAccount" - {
      "create pet service accounts" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.createPetServiceAccount(defaultPetSA, samRequestContext).unsafeRunSync() shouldBe defaultPetSA
      }
    }

    "loadPetServiceAccount" - {
      "load pet service accounts" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.createPetServiceAccount(defaultPetSA, samRequestContext).unsafeRunSync()

        dao.loadPetServiceAccount(defaultPetSA.id, samRequestContext).unsafeRunSync() shouldBe Some(defaultPetSA)
      }

      "return None for nonexistent pet service accounts" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.loadPetServiceAccount(defaultPetSA.id, samRequestContext).unsafeRunSync() shouldBe None
      }
    }

    "deletePetServiceAccount" - {
      "delete pet service accounts" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.createPetServiceAccount(defaultPetSA, samRequestContext).unsafeRunSync()

        dao.loadPetServiceAccount(defaultPetSA.id, samRequestContext).unsafeRunSync() shouldBe Some(defaultPetSA)

        dao.deletePetServiceAccount(defaultPetSA.id, samRequestContext).unsafeRunSync()

        dao.loadPetServiceAccount(defaultPetSA.id, samRequestContext).unsafeRunSync() shouldBe None
      }

      "throw an exception when trying to delete a nonexistent pet service account" in {
        assume(databaseEnabled, databaseEnabledClue)
        assertThrows[WorkbenchException] {
          dao.deletePetServiceAccount(defaultPetSA.id, samRequestContext).unsafeRunSync()
        }
      }
    }

    "getAllPetServiceAccountsForUser" - {
      "get all pet service accounts for user" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        val petSA1 = PetServiceAccount(
          PetServiceAccountId(defaultUser.id, GoogleProject("testProject1")),
          ServiceAccount(ServiceAccountSubjectId("testGoogleSubjectId1"), WorkbenchEmail("test1@pet.co"), ServiceAccountDisplayName("whoCares"))
        )
        dao.createPetServiceAccount(petSA1, samRequestContext).unsafeRunSync()

        val petSA2 = PetServiceAccount(
          PetServiceAccountId(defaultUser.id, GoogleProject("testProject2")),
          ServiceAccount(ServiceAccountSubjectId("testGoogleSubjectId2"), WorkbenchEmail("test2@pet.co"), ServiceAccountDisplayName("whoCares"))
        )
        dao.createPetServiceAccount(petSA2, samRequestContext).unsafeRunSync()

        dao.getAllPetServiceAccountsForUser(defaultUser.id, samRequestContext).unsafeRunSync() should contain theSameElementsAs Seq(petSA1, petSA2)
      }
    }

    "getUserFromPetServiceAccount" - {
      "get user from pet service account subject ID" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        dao.createPetServiceAccount(defaultPetSA, samRequestContext).unsafeRunSync()

        dao.getUserFromPetServiceAccount(defaultPetSA.serviceAccount.subjectId, samRequestContext).unsafeRunSync() shouldBe Some(defaultUser)
      }
    }

    "updatePetServiceAccount" - {
      "update a pet service account" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        dao.createPetServiceAccount(defaultPetSA, samRequestContext).unsafeRunSync()

        val updatedPetSA = defaultPetSA.copy(serviceAccount =
          ServiceAccount(ServiceAccountSubjectId("updatedTestGoogleSubjectId"), WorkbenchEmail("new@pet.co"), ServiceAccountDisplayName("whoCares"))
        )
        dao.updatePetServiceAccount(updatedPetSA, samRequestContext).unsafeRunSync() shouldBe updatedPetSA

        dao.loadPetServiceAccount(updatedPetSA.id, samRequestContext).unsafeRunSync() shouldBe Some(updatedPetSA)
      }

      "throw an exception when updating a nonexistent pet SA" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        val updatedPetSA = defaultPetSA.copy(serviceAccount =
          ServiceAccount(ServiceAccountSubjectId("updatedTestGoogleSubjectId"), WorkbenchEmail("new@pet.co"), ServiceAccountDisplayName("whoCares"))
        )
        assertThrows[WorkbenchException] {
          dao.updatePetServiceAccount(updatedPetSA, samRequestContext).unsafeRunSync() shouldBe updatedPetSA
        }
      }
    }

    "getManagedGroupAccessInstructions" - {
      "get managed group access instructions" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.getManagedGroupAccessInstructions(defaultGroupName, samRequestContext).unsafeRunSync() shouldBe None
      }
    }

    "setManagedGroupAccessInstructions" - {
      "set managed group access instructions" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.setManagedGroupAccessInstructions(defaultGroupName, "testinstructions", samRequestContext).unsafeRunSync()

        dao.getManagedGroupAccessInstructions(defaultGroupName, samRequestContext).unsafeRunSync() shouldBe Some("testinstructions")
      }
    }

    "isGroupMember" - {
      "return true when member is in sub group" in {
        assume(databaseEnabled, databaseEnabledClue)
        val subGroup1 = defaultGroup
        val subGroup2 = BasicWorkbenchGroup(WorkbenchGroupName("subGroup2"), Set(subGroup1.id), WorkbenchEmail("bar@baz.com"))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), Set(subGroup2.id), WorkbenchEmail("baz@qux.com"))

        dao.createGroup(subGroup1, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(subGroup2, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.isGroupMember(parentGroup.id, subGroup1.id, samRequestContext).unsafeRunSync() should be(true)
      }

      "return false when member is not in sub group" in {
        assume(databaseEnabled, databaseEnabledClue)
        val subGroup1 = defaultGroup
        val subGroup2 = BasicWorkbenchGroup(WorkbenchGroupName("subGroup2"), Set(subGroup1.id), WorkbenchEmail("bar@baz.com"))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), Set.empty, WorkbenchEmail("baz@qux.com"))

        dao.createGroup(subGroup1, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(subGroup2, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.isGroupMember(parentGroup.id, subGroup1.id, samRequestContext).unsafeRunSync() should be(false)
      }

      "return true when user is in sub group" in {
        assume(databaseEnabled, databaseEnabledClue)
        val user = defaultUser
        val subGroup = defaultGroup.copy(members = Set(user.id))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), Set(subGroup.id), WorkbenchEmail("parent@group.com"))

        dao.createUser(user, samRequestContext).unsafeRunSync()
        dao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.isGroupMember(parentGroup.id, user.id, samRequestContext).unsafeRunSync() shouldBe true
      }

      // https://broadworkbench.atlassian.net/browse/CA-600
      "return true when user is in multiple sub groups" in {
        assume(databaseEnabled, databaseEnabledClue)
        val user = defaultUser
        val subGroup1 = defaultGroup.copy(members = Set(user.id))
        val subGroup2 = BasicWorkbenchGroup(WorkbenchGroupName("subGroup2"), Set(user.id), WorkbenchEmail("group2@foo.com"))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), Set(subGroup1.id, subGroup2.id), WorkbenchEmail("baz@qux.com"))

        dao.createUser(user, samRequestContext).unsafeRunSync()
        dao.createGroup(subGroup1, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(subGroup2, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.isGroupMember(parentGroup.id, user.id, samRequestContext).unsafeRunSync() should be(true)
      }

      "return false when user is not in sub group" in {
        assume(databaseEnabled, databaseEnabledClue)
        val user = defaultUser
        val subGroup = defaultGroup.copy(members = Set(user.id))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), Set.empty, WorkbenchEmail("parent@group.com"))

        dao.createUser(user, samRequestContext).unsafeRunSync()
        dao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.isGroupMember(parentGroup.id, user.id, samRequestContext).unsafeRunSync() shouldBe false
      }

      "return true when user is in policy" in {
        assume(databaseEnabled, databaseEnabledClue)
        val user = defaultUser
        val policy = defaultPolicy.copy(members = Set(user.id))

        dao.createUser(user, samRequestContext).unsafeRunSync()
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(policy, samRequestContext).unsafeRunSync()

        dao.isGroupMember(policy.id, user.id, samRequestContext).unsafeRunSync() shouldBe true
      }

      "return false when user is not in policy" in {
        assume(databaseEnabled, databaseEnabledClue)
        val user = defaultUser
        val policy = defaultPolicy

        dao.createUser(user, samRequestContext).unsafeRunSync()
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(policy, samRequestContext).unsafeRunSync()

        dao.isGroupMember(policy.id, user.id, samRequestContext).unsafeRunSync() shouldBe false
      }

      "return true when policy is in policy" in {
        assume(databaseEnabled, databaseEnabledClue)
        val memberPolicy =
          defaultPolicy.copy(id = defaultPolicy.id.copy(accessPolicyName = AccessPolicyName("memberPolicy")), email = WorkbenchEmail("copied@policy.com"))
        val policy = defaultPolicy.copy(members = Set(memberPolicy.id))

        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(memberPolicy, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(policy, samRequestContext).unsafeRunSync()

        dao.isGroupMember(policy.id, memberPolicy.id, samRequestContext).unsafeRunSync() shouldBe true
      }

      "return false when policy is not in policy" in {
        assume(databaseEnabled, databaseEnabledClue)
        val memberPolicy =
          defaultPolicy.copy(id = defaultPolicy.id.copy(accessPolicyName = AccessPolicyName("memberPolicy")), email = WorkbenchEmail("copied@policy.com"))
        val policy = defaultPolicy

        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(memberPolicy, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(policy, samRequestContext).unsafeRunSync()

        dao.isGroupMember(policy.id, memberPolicy.id, samRequestContext).unsafeRunSync() shouldBe false
      }

      "return true when policy is in group" in {
        assume(databaseEnabled, databaseEnabledClue)
        val memberPolicy = defaultPolicy
        val group = defaultGroup.copy(members = Set(memberPolicy.id))

        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(memberPolicy, samRequestContext).unsafeRunSync()
        dao.createGroup(group, samRequestContext = samRequestContext).unsafeRunSync()

        dao.isGroupMember(group.id, memberPolicy.id, samRequestContext).unsafeRunSync() shouldBe true
      }

      "return false when policy is not in group" in {
        assume(databaseEnabled, databaseEnabledClue)
        val memberPolicy = defaultPolicy
        val group = defaultGroup

        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(memberPolicy, samRequestContext).unsafeRunSync()
        dao.createGroup(group, samRequestContext = samRequestContext).unsafeRunSync()

        dao.isGroupMember(group.id, memberPolicy.id, samRequestContext).unsafeRunSync() shouldBe false
      }

      "return true when group is in policy" in {
        assume(databaseEnabled, databaseEnabledClue)
        val memberGroup = defaultGroup
        val policy = defaultPolicy.copy(members = Set(memberGroup.id))

        dao.createGroup(memberGroup, samRequestContext = samRequestContext).unsafeRunSync()
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(policy, samRequestContext).unsafeRunSync()

        dao.isGroupMember(policy.id, memberGroup.id, samRequestContext).unsafeRunSync() shouldBe true
      }

      "return false when group is not in policy" in {
        assume(databaseEnabled, databaseEnabledClue)
        val memberGroup = defaultGroup
        val policy = defaultPolicy

        dao.createGroup(memberGroup, samRequestContext = samRequestContext).unsafeRunSync()
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(policy, samRequestContext).unsafeRunSync()

        dao.isGroupMember(policy.id, memberGroup.id, samRequestContext).unsafeRunSync() shouldBe false
      }
    }

    "listIntersectionGroupUsers" - {
      // DV: I have tried this up to 100 groups to intersect locally with no functional issue, performance seems linear
      "intersect groups" in {
        assume(databaseEnabled, databaseEnabledClue)
        for (groupCount <- 1 to 3) {
          beforeEach()
          val inAllGroups = Generator.genWorkbenchUserGoogle.sample.get
          dao.createUser(inAllGroups, samRequestContext).unsafeRunSync()

          val allGroups = for (i <- 1 to groupCount) yield {
            // create a group with 1 user and 1 subgroup, subgroup with "allgroups" users and another user
            val userInGroup = Generator.genWorkbenchUserGoogle.sample.get
            val userInSubGroup = Generator.genWorkbenchUserGoogle.sample.get
            val subGroup = BasicWorkbenchGroup(WorkbenchGroupName(s"subgroup$i"), Set(inAllGroups.id, userInSubGroup.id), WorkbenchEmail(s"subgroup$i"))
            val group = BasicWorkbenchGroup(WorkbenchGroupName(s"group$i"), Set(userInGroup.id, subGroup.id), WorkbenchEmail(s"group$i"))
            dao.createUser(userInSubGroup, samRequestContext).unsafeRunSync()
            dao.createUser(userInGroup, samRequestContext).unsafeRunSync()
            dao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
            dao.createGroup(group, samRequestContext = samRequestContext).unsafeRunSync()
          }

          if (groupCount == 1) {
            val intersection = dao.listIntersectionGroupUsers(allGroups.map(_.id).toSet, samRequestContext).unsafeRunSync()
            intersection should contain oneElementOf Set(inAllGroups.id)
            intersection.size shouldBe 3
          } else {
            dao.listIntersectionGroupUsers(allGroups.map(_.id).toSet, samRequestContext).unsafeRunSync() should contain theSameElementsAs Set(inAllGroups.id)
          }
        }
      }

      "intersect lots of groups with lots of dups and overlaps" in {
        assume(databaseEnabled, databaseEnabledClue)
        val groupCount = 40
        val userCount = 50

        // create a user and a group containing that single user
        val allUserGroups = for (i <- 1 to userCount) yield {
          val user = dao
            .createUser(
              Generator.genWorkbenchUserGoogle.sample.get
                .copy(id = WorkbenchUserId(s"user$i"), email = WorkbenchEmail(s"user$i@email"), googleSubjectId = None),
              samRequestContext
            )
            .unsafeRunSync()
          val group = BasicWorkbenchGroup(WorkbenchGroupName(s"usergroup$i"), Set(user.id), WorkbenchEmail(s"usergroup$i"))
          dao.createGroup(group, samRequestContext = samRequestContext).unsafeRunSync()
        }

        val allUserGroupNames: Set[WorkbenchSubject] = allUserGroups.map(_.id).toSet
        val allUserIds = allUserGroups.map(_.members.head)

        // create groupCount groups each containing all single user groups
        val allSubGroups = for (i <- 1 to groupCount) yield {
          val group = BasicWorkbenchGroup(WorkbenchGroupName(s"subgroup$i"), allUserGroupNames, WorkbenchEmail(s"subgroup$i"))
          dao.createGroup(group, samRequestContext = samRequestContext).unsafeRunSync()
        }

        // create groupCount groups each containing all subGroups
        val topGroups = for (i <- 1 to groupCount) yield {
          // create a group with 1 user and 1 subgroup, subgroup with "allgroups" users and another user
          val group = BasicWorkbenchGroup(WorkbenchGroupName(s"group$i"), allSubGroups.map(_.id).toSet, WorkbenchEmail(s"group$i"))
          dao.createGroup(group, samRequestContext = samRequestContext).unsafeRunSync()
        }

        // intersect all top groups
        dao.listIntersectionGroupUsers(topGroups.map(_.id).toSet, samRequestContext).unsafeRunSync() should contain theSameElementsAs allUserIds
      }
    }

    "enableIdentity and disableIdentity" - {
      "can enable and disable users" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe false

        dao.enableIdentity(defaultUser.id, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe true

        dao.disableIdentity(defaultUser.id, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe false
      }

      "cannot enable and disable pet service accounts" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.createPetServiceAccount(defaultPetSA, samRequestContext).unsafeRunSync()
        val initialEnabledStatus = dao.isEnabled(defaultPetSA.id, samRequestContext).unsafeRunSync()

        dao.disableIdentity(defaultPetSA.id, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultPetSA.id, samRequestContext).unsafeRunSync() shouldBe initialEnabledStatus

        dao.enableIdentity(defaultPetSA.id, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultPetSA.id, samRequestContext).unsafeRunSync() shouldBe initialEnabledStatus
      }

      "cannot enable and disable groups" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        val initialEnabledStatus = dao.isEnabled(defaultGroup.id, samRequestContext).unsafeRunSync()

        dao.disableIdentity(defaultGroup.id, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultGroup.id, samRequestContext).unsafeRunSync() shouldBe initialEnabledStatus

        dao.enableIdentity(defaultGroup.id, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultGroup.id, samRequestContext).unsafeRunSync() shouldBe initialEnabledStatus
      }

      "cannot enable and disable policies" in {
        assume(databaseEnabled, databaseEnabledClue)
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()
        val initialEnabledStatus = dao.isEnabled(defaultPolicy.id, samRequestContext).unsafeRunSync()

        dao.disableIdentity(defaultPolicy.id, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultPolicy.id, samRequestContext).unsafeRunSync() shouldBe initialEnabledStatus

        dao.enableIdentity(defaultPolicy.id, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultPolicy.id, samRequestContext).unsafeRunSync() shouldBe initialEnabledStatus
      }

      "enableIdentity" - {
        "sets the updatedAt datetime to the current datetime" in {
          assume(databaseEnabled, databaseEnabledClue)
          /// Arrange
          val user = Generator.genWorkbenchUserGoogle.sample.get.copy(
            enabled = false,
            updatedAt = Instant.parse("2020-02-02T20:20:20Z")
          )
          dao.createUser(user, samRequestContext).unsafeRunSync()

          // Act
          dao.enableIdentity(user.id, samRequestContext).unsafeRunSync()

          // Assert
          val loadedUser = dao.loadUser(user.id, samRequestContext).unsafeRunSync()
          loadedUser.value.updatedAt should beAround(Instant.now())
        }
      }

      "disableIdentity" - {
        "sets the updatedAt datetime to the current datetime" in {
          assume(databaseEnabled, databaseEnabledClue)
          /// Arrange
          val user = Generator.genWorkbenchUserGoogle.sample.get.copy(
            enabled = true,
            updatedAt = Instant.parse("2020-02-02T20:20:20Z")
          )
          dao.createUser(user, samRequestContext).unsafeRunSync()

          // Act
          dao.disableIdentity(user.id, samRequestContext).unsafeRunSync()

          // Assert
          val loadedUser = dao.loadUser(user.id, samRequestContext).unsafeRunSync()
          loadedUser.value.updatedAt should beAround(Instant.now())
        }
      }
    }

    "isEnabled" - {
      "gets a user's enabled status" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        dao.disableIdentity(defaultUser.id, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe false

        dao.enableIdentity(defaultUser.id, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe true
      }

      "gets a pet's user's enabled status" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.createPetServiceAccount(defaultPetSA, samRequestContext).unsafeRunSync()

        dao.disableIdentity(defaultUser.id, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultPetSA.id, samRequestContext).unsafeRunSync() shouldBe false

        dao.enableIdentity(defaultUser.id, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultPetSA.id, samRequestContext).unsafeRunSync() shouldBe true
      }

      "returns false for groups" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.isEnabled(defaultGroup.id, samRequestContext).unsafeRunSync() shouldBe false
        dao.enableIdentity(defaultGroup.id, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultGroup.id, samRequestContext).unsafeRunSync() shouldBe false
      }

      "returns false for policies" in {
        assume(databaseEnabled, databaseEnabledClue)
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()

        dao.isEnabled(defaultPolicy.id, samRequestContext).unsafeRunSync() shouldBe false
        dao.enableIdentity(defaultPolicy.id, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultPolicy.id, samRequestContext).unsafeRunSync() shouldBe false
      }
    }

    "listUserDirectMemberships" - {
      "lists all groups that a user is in directly" in {
        assume(databaseEnabled, databaseEnabledClue)
        val subSubGroup = BasicWorkbenchGroup(WorkbenchGroupName("ssg"), Set(defaultUser.id), WorkbenchEmail("ssg@groups.r.us"))
        val subGroup = BasicWorkbenchGroup(WorkbenchGroupName("sg"), Set(defaultUser.id, subSubGroup.id), WorkbenchEmail("sg@groups.r.us"))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("pg"), Set(subGroup.id), WorkbenchEmail("pg@groups.r.us"))

        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.createGroup(subSubGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.listUserDirectMemberships(defaultUser.id, samRequestContext).unsafeRunSync() should contain theSameElementsAs Set(subGroup.id, subSubGroup.id)
      }

      "lists all policies that a user is in directly" in {
        assume(databaseEnabled, databaseEnabledClue)
        // disclaimer: not sure this ever happens in actual sam usage, but it should still work
        val subSubPolicy = defaultPolicy.copy(
          id = defaultPolicy.id.copy(accessPolicyName = AccessPolicyName("ssp")),
          email = WorkbenchEmail("ssp@policy.com"),
          members = Set(defaultUser.id)
        )
        val subPolicy = defaultPolicy.copy(
          id = defaultPolicy.id.copy(accessPolicyName = AccessPolicyName("sp")),
          email = WorkbenchEmail("sp@policy.com"),
          members = Set(subSubPolicy.id, defaultUser.id)
        )
        val parentPolicy = defaultPolicy.copy(
          id = defaultPolicy.id.copy(accessPolicyName = AccessPolicyName("pp")),
          email = WorkbenchEmail("pp@policy.com"),
          members = Set(subPolicy.id)
        )

        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(subSubPolicy, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(subPolicy, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(parentPolicy, samRequestContext).unsafeRunSync()

        dao.listUserDirectMemberships(defaultUser.id, samRequestContext).unsafeRunSync() should contain theSameElementsAs Set(subSubPolicy.id, subPolicy.id)
      }
    }

    "listAncestorGroups" - {
      "list all of the groups a group is in" in {
        assume(databaseEnabled, databaseEnabledClue)
        val subSubGroup = BasicWorkbenchGroup(WorkbenchGroupName("ssg"), Set.empty, WorkbenchEmail("ssg@groups.r.us"))
        val subGroup = BasicWorkbenchGroup(WorkbenchGroupName("sg"), Set(subSubGroup.id), WorkbenchEmail("sg@groups.r.us"))
        val directParentGroup = BasicWorkbenchGroup(WorkbenchGroupName("dpg"), Set(subGroup.id, subSubGroup.id), WorkbenchEmail("dpg@groups.r.us"))
        val indirectParentGroup = BasicWorkbenchGroup(WorkbenchGroupName("ipg"), Set(subGroup.id), WorkbenchEmail("ipg@groups.r.us"))

        dao.createGroup(subSubGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(directParentGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(indirectParentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        val ancestorGroups = dao.listAncestorGroups(subSubGroup.id, samRequestContext).unsafeRunSync()
        ancestorGroups should contain theSameElementsAs Set(subGroup.id, directParentGroup.id, indirectParentGroup.id)
      }

      "list all of the policies a group is in" in {
        assume(databaseEnabled, databaseEnabledClue)
        val subPolicy = defaultPolicy.copy(
          id = defaultPolicy.id.copy(accessPolicyName = AccessPolicyName("sp")),
          email = WorkbenchEmail("sp@policy.com"),
          members = Set(defaultGroup.id)
        )
        val parentPolicy = defaultPolicy.copy(
          id = defaultPolicy.id.copy(accessPolicyName = AccessPolicyName("pp")),
          email = WorkbenchEmail("pp@policy.com"),
          members = Set(subPolicy.id)
        )

        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(subPolicy, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(parentPolicy, samRequestContext).unsafeRunSync()

        dao.listAncestorGroups(defaultGroup.id, samRequestContext).unsafeRunSync() should contain theSameElementsAs Set(subPolicy.id, parentPolicy.id)
      }

      "list all of the groups a policy is in" in {
        assume(databaseEnabled, databaseEnabledClue)
        val subGroup = BasicWorkbenchGroup(WorkbenchGroupName("sg"), Set(defaultPolicy.id), WorkbenchEmail("sg@groups.r.us"))
        val directParentGroup = BasicWorkbenchGroup(WorkbenchGroupName("dpg"), Set(subGroup.id, defaultPolicy.id), WorkbenchEmail("dpg@groups.r.us"))
        val indirectParentGroup = BasicWorkbenchGroup(WorkbenchGroupName("ipg"), Set(subGroup.id), WorkbenchEmail("ipg@groups.r.us"))

        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()
        dao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(directParentGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(indirectParentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        val ancestorGroups = dao.listAncestorGroups(defaultPolicy.id, samRequestContext).unsafeRunSync()
        ancestorGroups should contain theSameElementsAs Set(subGroup.id, directParentGroup.id, indirectParentGroup.id)
      }

      "list all of the policies a policy is in" in {
        assume(databaseEnabled, databaseEnabledClue)
        val subPolicy = defaultPolicy.copy(
          id = defaultPolicy.id.copy(accessPolicyName = AccessPolicyName("sp")),
          email = WorkbenchEmail("sp@policy.com"),
          members = Set(defaultPolicy.id)
        )
        val directParentPolicy = defaultPolicy.copy(
          id = defaultPolicy.id.copy(accessPolicyName = AccessPolicyName("pp")),
          email = WorkbenchEmail("pp@policy.com"),
          members = Set(subPolicy.id, defaultPolicy.id)
        )
        val indirectParentPolicy = defaultPolicy.copy(
          id = defaultPolicy.id.copy(accessPolicyName = AccessPolicyName("ssp")),
          email = WorkbenchEmail("ssp@policy.com"),
          members = Set(subPolicy.id)
        )

        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(subPolicy, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(directParentPolicy, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(indirectParentPolicy, samRequestContext).unsafeRunSync()

        val ancestorGroups = dao.listAncestorGroups(defaultPolicy.id, samRequestContext).unsafeRunSync()
        ancestorGroups should contain theSameElementsAs Set(subPolicy.id, directParentPolicy.id, indirectParentPolicy.id)
      }
    }

    "getSynchronizedEmail" - {
      "load the email for a group" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.getSynchronizedEmail(defaultGroup.id, samRequestContext).unsafeRunSync() shouldEqual Option(defaultGroup.email)
      }

      "load the email for a policy" in {
        assume(databaseEnabled, databaseEnabledClue)
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()

        dao.getSynchronizedEmail(defaultPolicy.id, samRequestContext).unsafeRunSync() shouldEqual Option(defaultPolicy.email)
      }
    }

    "getSynchronizedDate" - {
      "load the synchronized date for a group" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.updateSynchronizedDateAndVersion(defaultGroup, samRequestContext).unsafeRunSync()

        val loadedDate = dao.getSynchronizedDate(defaultGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail("failed to load date"))
        loadedDate.getTime should equal(new Date().getTime +- 2.seconds.toMillis)
      }

      "load the synchronized date for a policy" in {
        assume(databaseEnabled, databaseEnabledClue)
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()

        dao.updateSynchronizedDateAndVersion(defaultPolicy, samRequestContext).unsafeRunSync()

        val loadedDate = dao.getSynchronizedDate(defaultPolicy.id, samRequestContext).unsafeRunSync().getOrElse(fail("failed to load date"))
        loadedDate.getTime should equal(new Date().getTime +- 2.seconds.toMillis)
      }
    }

    "updateUser" - {
      "will null the googleSubjectId for a user when provided googleSubjectId is 'null'" in {
        assume(databaseEnabled, databaseEnabledClue)
        val user = genWorkbenchUserBoth.sample.get
        dao.createUser(user, samRequestContext).unsafeRunSync()

        dao.loadUser(user.id, samRequestContext).unsafeRunSync().flatMap(_.googleSubjectId) shouldBe user.googleSubjectId
        dao.updateUser(user, AdminUpdateUserRequest(None, Option(GoogleSubjectId("null"))), samRequestContext).unsafeRunSync()

        dao.loadUser(user.id, samRequestContext).unsafeRunSync().flatMap(_.googleSubjectId) shouldBe None
      }

      "will null the azureB2CId for a user when provided azureB2CId is 'null'" in {
        assume(databaseEnabled, databaseEnabledClue)
        val user = genWorkbenchUserBoth.sample.get
        dao.createUser(user, samRequestContext).unsafeRunSync()

        dao.loadUser(user.id, samRequestContext).unsafeRunSync().flatMap(_.azureB2CId) shouldBe user.azureB2CId
        dao.updateUser(user, AdminUpdateUserRequest(Option(AzureB2CId("null")), None), samRequestContext).unsafeRunSync()

        dao.loadUser(user.id, samRequestContext).unsafeRunSync().flatMap(_.azureB2CId) shouldBe None
      }

      "update the googleSubjectId for a user" in {
        assume(databaseEnabled, databaseEnabledClue)
        val newGoogleSubjectId = GoogleSubjectId("newGoogleSubjectId")
        val user = Generator.genWorkbenchUserAzure.sample.get
        dao.createUser(user, samRequestContext).unsafeRunSync()

        dao.loadUser(user.id, samRequestContext).unsafeRunSync().flatMap(_.googleSubjectId) shouldBe None
        dao.updateUser(user, AdminUpdateUserRequest(None, Option(newGoogleSubjectId)), samRequestContext).unsafeRunSync()

        dao.loadUser(user.id, samRequestContext).unsafeRunSync().flatMap(_.googleSubjectId) shouldBe Option(newGoogleSubjectId)
      }

      "update the azureB2CId for a user" in {
        assume(databaseEnabled, databaseEnabledClue)
        val newB2CId = AzureB2CId(UUID.randomUUID().toString)
        val user = Generator.genWorkbenchUserGoogle.sample.get
        dao.createUser(user, samRequestContext).unsafeRunSync()

        dao.loadUser(user.id, samRequestContext).unsafeRunSync().flatMap(_.azureB2CId) shouldBe None
        dao.updateUser(user, AdminUpdateUserRequest(Option(newB2CId), None), samRequestContext).unsafeRunSync()

        dao.loadUser(user.id, samRequestContext).unsafeRunSync().flatMap(_.azureB2CId) shouldBe Option(newB2CId)
      }

      "sets the updatedAt datetime to the current datetime" in {
        assume(databaseEnabled, databaseEnabledClue)
        // Arrange
        val user = Generator.genWorkbenchUserGoogle.sample.get.copy(
          updatedAt = Instant.parse("2020-02-02T20:20:20Z")
        )
        dao.createUser(user, samRequestContext).unsafeRunSync()
        val newGoogleSubjectId = GoogleSubjectId("newGoogleSubjectId")

        // Act
        dao.updateUser(user, AdminUpdateUserRequest(None, Option(newGoogleSubjectId)), samRequestContext).unsafeRunSync()

        // Assert
        val loadedUser = dao.loadUser(user.id, samRequestContext).unsafeRunSync()
        loadedUser.value.updatedAt should beAround(Instant.now())
      }

      "will update the googleSubjectId and azureB2CId for a user" in {
        assume(databaseEnabled, databaseEnabledClue)
        val newGoogleSubjectId = GoogleSubjectId("234567890123456789012")
        val newB2CId = AzureB2CId(UUID.randomUUID().toString)
        val user = Generator.genWorkbenchUserBoth.sample.get
        dao.createUser(user, samRequestContext).unsafeRunSync()

        dao.updateUser(user, AdminUpdateUserRequest(Option(newB2CId), Option(newGoogleSubjectId)), samRequestContext).unsafeRunSync()

        val updatedUser = dao.loadUser(user.id, samRequestContext).unsafeRunSync()
        updatedUser.flatMap(_.googleSubjectId) shouldBe Option(newGoogleSubjectId)
        updatedUser.flatMap(_.azureB2CId) shouldBe Option(newB2CId)
      }
    }

    "setGoogleSubjectId" - {
      "update the googleSubjectId for a user" in {
        assume(databaseEnabled, databaseEnabledClue)
        val newGoogleSubjectId = GoogleSubjectId("newGoogleSubjectId")
        dao.createUser(defaultUser.copy(googleSubjectId = None), samRequestContext).unsafeRunSync()

        dao.loadUser(defaultUser.id, samRequestContext).unsafeRunSync().flatMap(_.googleSubjectId) shouldBe None
        dao.setGoogleSubjectId(defaultUser.id, newGoogleSubjectId, samRequestContext).unsafeRunSync()

        dao.loadUser(defaultUser.id, samRequestContext).unsafeRunSync().flatMap(_.googleSubjectId) shouldBe Option(newGoogleSubjectId)
      }

      "sets the updatedAt datetime to the current datetime" in {
        assume(databaseEnabled, databaseEnabledClue)
        // Arrange
        val user = Generator.genWorkbenchUserGoogle.sample.get.copy(
          googleSubjectId = None,
          updatedAt = Instant.parse("2020-02-02T20:20:20Z")
        )
        dao.createUser(user, samRequestContext).unsafeRunSync()
        val newGoogleSubjectId = GoogleSubjectId("newGoogleSubjectId")

        // Act
        dao.setGoogleSubjectId(user.id, newGoogleSubjectId, samRequestContext).unsafeRunSync()

        // Assert
        val loadedUser = dao.loadUser(user.id, samRequestContext).unsafeRunSync()
        loadedUser.value.updatedAt should beAround(Instant.now())
      }

      // Making an assumption that this is the intended behavior/UX we want?  :shrug:  This is a weird scenario that I
      // cannot imagine we would be in unless something went wrong.  The test is handy though to make sure the sql is
      // working correctly.
      "does not change the registeredAt datetime if it is already set" in {
        assume(databaseEnabled, databaseEnabledClue)
        // Arrange
        val expectedRegisteredAt = Instant.parse("2022-02-22T22:22:22Z")
        val user = Generator.genWorkbenchUserAzure.sample.get.copy(googleSubjectId = None, azureB2CId = None, registeredAt = Option(expectedRegisteredAt))
        dao.createUser(user, samRequestContext).unsafeRunSync()
        val newGoogleSubjectId = GoogleSubjectId("newGoogleSubjectId")

        // Act
        dao.setGoogleSubjectId(user.id, newGoogleSubjectId, samRequestContext).unsafeRunSync()

        // Assert
        val loadedUser = dao.loadUser(user.id, samRequestContext).unsafeRunSync()
        inside(loadedUser.value) { user =>
          user.registeredAt.value should equal(expectedRegisteredAt)
        }
      }
    }

    "loadSubjectFromEmail" - {
      "load a user subject from their email" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        dao.loadSubjectFromEmail(defaultUser.email, samRequestContext).unsafeRunSync() shouldBe Some(defaultUser.id)
      }

      "load a user subject from their email case insensitive" in {
        assume(databaseEnabled, databaseEnabledClue)
        val email = WorkbenchEmail("Mixed.Case.Email@foo.com")
        dao.createUser(defaultUser.copy(email = email), samRequestContext).unsafeRunSync()

        dao.loadSubjectFromEmail(WorkbenchEmail(email.value.toLowerCase()), samRequestContext).unsafeRunSync() shouldBe Some(defaultUser.id)
      }

      "load a group subject from its email" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.loadSubjectFromEmail(defaultGroup.email, samRequestContext).unsafeRunSync() shouldBe Some(defaultGroupName)
      }

      "load a pet service account subject from its email" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.createPetServiceAccount(defaultPetSA, samRequestContext).unsafeRunSync()

        dao.loadSubjectFromEmail(defaultPetSA.serviceAccount.email, samRequestContext).unsafeRunSync() shouldBe Some(defaultPetSA.id)
      }

      "load a policy subject from its email" in {
        assume(databaseEnabled, databaseEnabledClue)
        val memberPolicy = defaultPolicy

        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(memberPolicy, samRequestContext).unsafeRunSync()

        dao.loadSubjectFromEmail(defaultPolicy.email, samRequestContext).unsafeRunSync() shouldBe Some(defaultPolicy.id)
      }

      "throw an exception when an email refers to more than one subject" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao
          .createPetServiceAccount(defaultPetSA.copy(serviceAccount = defaultPetSA.serviceAccount.copy(email = defaultUser.email)), samRequestContext)
          .unsafeRunSync()

        assertThrows[WorkbenchException] {
          dao.loadSubjectFromEmail(defaultUser.email, samRequestContext).unsafeRunSync() shouldBe Some(defaultPetSA.id)
        }
      }
    }

    "loadSubjectFromGoogleSubjectId" - {
      "load a user subject from their google subject id" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        dao.loadSubjectFromGoogleSubjectId(defaultUser.googleSubjectId.get, samRequestContext).unsafeRunSync() shouldBe Some(defaultUser.id)
      }

      "load a pet service account subject from its google subject id" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.createPetServiceAccount(defaultPetSA, samRequestContext).unsafeRunSync()

        dao.loadSubjectFromGoogleSubjectId(GoogleSubjectId(defaultPetSA.serviceAccount.subjectId.value), samRequestContext).unsafeRunSync() shouldBe Some(
          defaultPetSA.id
        )
      }
    }

    "loadSubjectEmail" - {
      "load the email for a user" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        dao.loadSubjectEmail(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe Some(defaultUser.email)
      }

      "load the email for a group" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.loadSubjectEmail(defaultGroup.id, samRequestContext).unsafeRunSync() shouldBe Some(defaultGroup.email)
      }

      "load the email for a pet service account" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.createPetServiceAccount(defaultPetSA, samRequestContext).unsafeRunSync()

        dao.loadSubjectEmail(defaultPetSA.id, samRequestContext).unsafeRunSync() shouldBe Some(defaultPetSA.serviceAccount.email)
      }

      "load the email for a policy" in {
        assume(databaseEnabled, databaseEnabledClue)
        val memberPolicy = defaultPolicy

        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(memberPolicy, samRequestContext).unsafeRunSync()

        dao.loadSubjectEmail(defaultPolicy.id, samRequestContext).unsafeRunSync() shouldBe Some(defaultPolicy.email)
      }
    }

    "loadUserByAzureB2CId" - {
      "load a user from their azure b2c id" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        dao.loadUserByAzureB2CId(defaultUser.azureB2CId.get, samRequestContext).unsafeRunSync() shouldBe Some(defaultUser)
      }
    }

    "setUserAzureB2CId" - {
      "set the azureB2CId for a user with no pre-existing azureB2CId" in {
        assume(databaseEnabled, databaseEnabledClue)
        val newAzureB2cId = AzureB2CId("newAzureB2cId")
        dao.createUser(defaultUser.copy(azureB2CId = None), samRequestContext).unsafeRunSync()

        dao.loadUser(defaultUser.id, samRequestContext).unsafeRunSync().flatMap(_.azureB2CId) shouldBe None
        dao.setUserAzureB2CId(defaultUser.id, newAzureB2cId, samRequestContext).unsafeRunSync()

        dao.loadUser(defaultUser.id, samRequestContext).unsafeRunSync().flatMap(_.azureB2CId) shouldBe Option(newAzureB2cId)
      }

      "set the azureB2CId for a user with a pre-existing azureB2CId of the same value" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        dao.setUserAzureB2CId(defaultUser.id, defaultUser.azureB2CId.get, samRequestContext).unsafeRunSync()

        dao.loadUser(defaultUser.id, samRequestContext).unsafeRunSync().flatMap(_.azureB2CId) shouldBe Option(defaultUser.azureB2CId.get)
      }

      "throw an exception when trying to set azureB2CId for a non-existing user" in {
        assume(databaseEnabled, databaseEnabledClue)
        val newAzureB2cId = AzureB2CId("newAzureB2cId")

        assertThrows[WorkbenchException] {
          dao.setUserAzureB2CId(defaultUser.id, newAzureB2cId, samRequestContext).unsafeRunSync()
        }
      }

      "sets the updatedAt datetime to the current datetime" in {
        assume(databaseEnabled, databaseEnabledClue)
        // Arrange
        val user = Generator.genWorkbenchUserAzure.sample.get.copy(
          azureB2CId = None,
          updatedAt = Instant.parse("2020-02-02T20:20:20Z")
        )
        dao.createUser(user, samRequestContext).unsafeRunSync()
        val newAzureB2cId = AzureB2CId("newAzureB2cId")

        // Act
        dao.setUserAzureB2CId(user.id, newAzureB2cId, samRequestContext).unsafeRunSync()

        // Assert
        val loadedUser = dao.loadUser(user.id, samRequestContext).unsafeRunSync()
        loadedUser.value.updatedAt should beAround(Instant.now())
      }

      // Making an assumption that this is the intended behavior/UX we want?  :shrug:  This is a weird scenario that I
      // cannot imagine we would be in unless something went wrong.  The test is handy though to make sure the sql is
      // working correctly.
      "does not change the registeredAt datetime if it is already set" in {
        assume(databaseEnabled, databaseEnabledClue)
        // Arrange
        val expectedRegisteredAt = Instant.parse("2022-02-22T22:22:22Z")
        val user = Generator.genWorkbenchUserAzure.sample.get.copy(
          googleSubjectId = None,
          azureB2CId = None,
          registeredAt = Option(expectedRegisteredAt)
        )
        dao.createUser(user, samRequestContext).unsafeRunSync()
        val newAzureB2CId = AzureB2CId("newAzureB2cId")

        // Act
        dao.setUserAzureB2CId(user.id, newAzureB2CId, samRequestContext).unsafeRunSync()

        // Assert
        val loadedUser = dao.loadUser(user.id, samRequestContext).unsafeRunSync()
        inside(loadedUser.value) { user =>
          user.registeredAt.value should equal(expectedRegisteredAt)
        }
      }
    }

    "createPetManagedIdentity" - {
      "create pet managed identity" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.createPetManagedIdentity(defaultPetMI, samRequestContext).unsafeRunSync() shouldBe defaultPetMI
      }
    }

    "loadPetManagedIdentity" - {
      "load pet managed identity" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.createPetManagedIdentity(defaultPetMI, samRequestContext).unsafeRunSync()

        dao.loadPetManagedIdentity(defaultPetMI.id, samRequestContext).unsafeRunSync() shouldBe Some(defaultPetMI)
      }

      "return None for nonexistent pet managed identities" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.loadPetManagedIdentity(defaultPetMI.id, samRequestContext).unsafeRunSync() shouldBe None
      }
    }

    "acceptTermsOfService" - {
      "accept the terms of service for a new user" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.acceptTermsOfService(defaultUser.id, tosConfig.version, samRequestContext).unsafeRunSync() shouldBe true

        // Assert
        val userTos = dao.getUserTermsOfService(defaultUser.id, samRequestContext).unsafeRunSync()
        userTos should not be empty
        userTos.get.createdAt should beAround(Instant.now())
        userTos.get.action shouldBe TosTable.ACCEPT
        userTos.get.version shouldBe tosConfig.version
      }

      "accept the terms of service for a user who has already accepted a previous version of the terms of service" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.acceptTermsOfService(defaultUser.id, "0", samRequestContext).unsafeRunSync() shouldBe true
        dao.acceptTermsOfService(defaultUser.id, "2", samRequestContext).unsafeRunSync() shouldBe true

        // Assert
        val userTos = dao.getUserTermsOfService(defaultUser.id, samRequestContext).unsafeRunSync()
        userTos should not be empty
        userTos.get.createdAt should beAround(Instant.now())
        userTos.get.action shouldBe TosTable.ACCEPT
        userTos.get.version shouldBe "2"
      }
    }

    "rejectTermsOfService" - {
      "reject the terms of service for an new user" in {
        assume(databaseEnabled, databaseEnabledClue)
        val user = Generator.genWorkbenchUserGoogle.sample.get
        dao.createUser(user, samRequestContext).unsafeRunSync()
        dao.rejectTermsOfService(user.id, tosConfig.version, samRequestContext).unsafeRunSync() shouldBe true

        // Assert
        val userTos = dao.getUserTermsOfService(user.id, samRequestContext).unsafeRunSync()
        userTos should not be empty
        userTos.get.createdAt should beAround(Instant.now())
        userTos.get.action shouldBe TosTable.REJECT
        userTos.get.version shouldBe tosConfig.version
      }

      "reject the terms of service for an existing user" in {
        assume(databaseEnabled, databaseEnabledClue)
        val user = Generator.genWorkbenchUserGoogle.sample.get
        dao.createUser(user, samRequestContext).unsafeRunSync()
        dao.acceptTermsOfService(user.id, tosConfig.version, samRequestContext).unsafeRunSync() shouldBe true
        dao.rejectTermsOfService(user.id, tosConfig.version, samRequestContext).unsafeRunSync() shouldBe true

        // Assert
        val userTos = dao.getUserTermsOfService(user.id, samRequestContext).unsafeRunSync()
        userTos should not be empty
        userTos.get.createdAt should beAround(Instant.now())
        userTos.get.action shouldBe TosTable.REJECT
        userTos.get.version shouldBe tosConfig.version
      }
    }

    "load terms of service" - {
      "returns none if no record" in {
        assume(databaseEnabled, databaseEnabledClue)
        val user = Generator.genWorkbenchUserGoogle.sample.get
        dao.createUser(user, samRequestContext).unsafeRunSync()

        // Assert
        val userTos = dao.getUserTermsOfService(user.id, samRequestContext).unsafeRunSync()
        userTos should be(None)
      }
      "returns acceptances" in {
        assume(databaseEnabled, databaseEnabledClue)
        val user = Generator.genWorkbenchUserGoogle.sample.get
        dao.createUser(user, samRequestContext).unsafeRunSync()

        dao.acceptTermsOfService(user.id, tosConfig.version, samRequestContext).unsafeRunSync() shouldBe true
        dao.rejectTermsOfService(user.id, tosConfig.version, samRequestContext).unsafeRunSync() shouldBe true

        // Assert
        val userTos = dao.getUserTermsOfService(user.id, samRequestContext, action = Option(TosTable.ACCEPT)).unsafeRunSync()
        userTos should be(Some(SamUserTos(user.id, tosConfig.version, TosTable.ACCEPT, Instant.now())))
      }
    }

    "checkStatus" - {
      "is true if database is queryable" in {
        assume(databaseEnabled, databaseEnabledClue)
        // Act
        val samStatus = dao.checkStatus(samRequestContext).unsafeRunSync()

        // Assert
        samStatus shouldBe true
      }

      "is false if database cannot be connected to" in {
        assume(databaseEnabled, databaseEnabledClue)
        // Arrange
        val badDBName = Symbol("noDB")
        val badDbReference = new TestDbReference(badDBName, TestSupport.blockingEc)
        val badDao = new PostgresDirectoryDAO(badDbReference, badDbReference)

        // Act
        val samStatus = badDao.checkStatus(samRequestContext).unsafeRunSync()

        // Assert
        samStatus shouldBe false
      }

      // Test ignored - not sure how to test it without injecting a mocked DBReference into the DAO or maybe passing
      // a bad query as a parameter into the checkStatus method
      "is false if database has connections but cannot be queried" in {
        assume(databaseEnabled, databaseEnabledClue)
        // Act
        val samStatus = dao.checkStatusWithQuery(samsqls"SELECT FOO FROM BAR", samRequestContext).unsafeRunSync()

        // Assert
        samStatus shouldBe false
      }
    }

    "setUserRegisteredAt" - {
      "sets the user's registeredAt column if its not yet set" in {
        assume(databaseEnabled, databaseEnabledClue)
        // Arrange
        val registeredAt = Instant.now().minus(10, ChronoUnit.MINUTES)
        val user = Generator.genWorkbenchUserGoogle.sample.get.copy(registeredAt = None)
        dao.createUser(user, samRequestContext).unsafeRunSync()

        // Act
        dao.setUserRegisteredAt(user.id, registeredAt, samRequestContext).unsafeRunSync()

        // Assert
        val loadedUser = dao.loadUser(user.id, samRequestContext).unsafeRunSync()
        loadedUser.value.registeredAt.get should beAround(registeredAt)
      }

      "refuses to overwrite the user's registeredAt date" in {
        assume(databaseEnabled, databaseEnabledClue)
        // Arrange
        val registeredAt = Instant.now()
        val user = Generator.genWorkbenchUserGoogle.sample.get.copy(registeredAt = Some(registeredAt))
        dao.createUser(user, samRequestContext).unsafeRunSync()

        // Act + Assert
        assertThrows[WorkbenchException] {
          dao.setUserRegisteredAt(user.id, Instant.now().minus(10, ChronoUnit.MINUTES), samRequestContext).unsafeRunSync()
        }

        val loadedUser = dao.loadUser(user.id, samRequestContext).unsafeRunSync()
        loadedUser.value.registeredAt.get should beAround(registeredAt)
      }

      "refuses to update the registeredAt date for a non-existent user" in {
        assume(databaseEnabled, databaseEnabledClue)
        // Arrange
        val registeredAt = Instant.now()
        val user = Generator.genWorkbenchUserGoogle.sample.get.copy(registeredAt = Some(registeredAt))

        // Act + Assert
        assertThrows[WorkbenchException] {
          dao.setUserRegisteredAt(user.id, Instant.now().minus(10, ChronoUnit.MINUTES), samRequestContext).unsafeRunSync()
        }
      }
    }

    "getUserAttributes" - {
      "gets the user attributes of an existing user" in {
        assume(databaseEnabled, databaseEnabledClue)
        // Arrange
        val expectedUser = defaultUser.copy(registeredAt = Some(Instant.now()))
        val createdUser = dao.createUser(expectedUser, samRequestContext).unsafeRunSync()
        val userAttributes = SamUserAttributes(createdUser.id, marketingConsent = true)
        dao.setUserAttributes(userAttributes, samRequestContext).unsafeRunSync()

        // Act
        val retrievedAttributes = dao.getUserAttributes(createdUser.id, samRequestContext).unsafeRunSync()

        // Assert
        retrievedAttributes should be(Some(userAttributes))
      }

      "returns None if no user attributes exist" in {
        assume(databaseEnabled, databaseEnabledClue)
        // Arrange
        val expectedUser = defaultUser.copy(registeredAt = Some(Instant.now()))
        val createdUser = dao.createUser(expectedUser, samRequestContext).unsafeRunSync()

        // Act
        val retrievedAttributes = dao.getUserAttributes(createdUser.id, samRequestContext).unsafeRunSync()

        // Assert
        retrievedAttributes should be(None)
      }

      "returns None if the user does not exist" in {
        assume(databaseEnabled, databaseEnabledClue)
        // Arrange
        // Act
        val retrievedAttributes = dao.getUserAttributes(defaultUser.id, samRequestContext).unsafeRunSync()

        // Assert
        retrievedAttributes should be(None)
      }
    }

    "setUserAttributes" - {
      "upserts new user attributes if user attributes already exist" in {
        assume(databaseEnabled, databaseEnabledClue)
        // Arrange
        val expectedUser = defaultUser.copy(registeredAt = Some(Instant.now()))
        val createdUser = dao.createUser(expectedUser, samRequestContext).unsafeRunSync()
        val userAttributes = SamUserAttributes(createdUser.id, marketingConsent = true)
        dao.setUserAttributes(userAttributes, samRequestContext).unsafeRunSync()

        // Act
        val upsertedAttributes = userAttributes.copy(marketingConsent = false)
        dao.setUserAttributes(upsertedAttributes, samRequestContext).unsafeRunSync()
        val retrievedAttributes = dao.getUserAttributes(expectedUser.id, samRequestContext).unsafeRunSync()

        // Assert
        retrievedAttributes should be(Some(upsertedAttributes))
      }
    }

    "Action Managed Identities" - {
      "can be individually created, read, updated, and deleted" in {
        assume(databaseEnabled, databaseEnabledClue)
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultBillingProfileResource, samRequestContext).unsafeRunSync()
        azureManagedResourceGroupDAO.insertManagedResourceGroup(defaultManagedResourceGroup, samRequestContext).unsafeRunSync()

        defaultActionManagedIdentities.map(dao.createActionManagedIdentity(_, samRequestContext).unsafeRunSync())

        val readActionManagedIdentity = defaultActionManagedIdentities.find(_.id.action == readAction)
        val loadedReadActionManagedIdentity = dao.loadActionManagedIdentity(readActionManagedIdentity.get.id, samRequestContext).unsafeRunSync()
        loadedReadActionManagedIdentity should be(readActionManagedIdentity)

        val writeActionManagedIdentity = defaultActionManagedIdentities.find(_.id.action == writeAction)
        val loadedWriteActionManagedIdentity = dao.loadActionManagedIdentity(writeActionManagedIdentity.get.id, samRequestContext).unsafeRunSync()
        loadedWriteActionManagedIdentity should be(writeActionManagedIdentity)

        val updatedActionManagedIdentity = writeActionManagedIdentity.get.copy(
          objectId = ManagedIdentityObjectId(UUID.randomUUID().toString),
          displayName = ManagedIdentityDisplayName("newDisplayName")
        )

        dao.updateActionManagedIdentity(updatedActionManagedIdentity, samRequestContext).unsafeRunSync()

        val loadedUpdatedActionManagedIdentity = dao.loadActionManagedIdentity(updatedActionManagedIdentity.id, samRequestContext).unsafeRunSync()
        loadedUpdatedActionManagedIdentity should be(Some(updatedActionManagedIdentity))

        dao.deleteActionManagedIdentity(readActionManagedIdentity.get.id, samRequestContext).unsafeRunSync()
        dao.deleteActionManagedIdentity(writeActionManagedIdentity.get.id, samRequestContext).unsafeRunSync()

        dao.loadActionManagedIdentity(readActionManagedIdentity.get.id, samRequestContext).unsafeRunSync() should be(None)
        dao.loadActionManagedIdentity(writeActionManagedIdentity.get.id, samRequestContext).unsafeRunSync() should be(None)
      }

      "can be loaded for a resource and action" in {
        assume(databaseEnabled, databaseEnabledClue)
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultBillingProfileResource, samRequestContext).unsafeRunSync()
        azureManagedResourceGroupDAO.insertManagedResourceGroup(defaultManagedResourceGroup, samRequestContext).unsafeRunSync()

        defaultActionManagedIdentities.map(dao.createActionManagedIdentity(_, samRequestContext).unsafeRunSync())

        val readActionManagedIdentity = defaultActionManagedIdentities.find(_.id.action == readAction)
        val loadedReadActionManagedIdentity = dao.loadActionManagedIdentity(defaultResource.fullyQualifiedId, readAction, samRequestContext).unsafeRunSync()
        loadedReadActionManagedIdentity should be(readActionManagedIdentity)

        val writeActionManagedIdentity = defaultActionManagedIdentities.find(_.id.action == writeAction)
        val loadedWriteActionManagedIdentity = dao.loadActionManagedIdentity(defaultResource.fullyQualifiedId, writeAction, samRequestContext).unsafeRunSync()
        loadedWriteActionManagedIdentity should be(writeActionManagedIdentity)
      }

      "can be read, and deleted en mass for a resource" in {
        assume(databaseEnabled, databaseEnabledClue)
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultBillingProfileResource, samRequestContext).unsafeRunSync()
        azureManagedResourceGroupDAO.insertManagedResourceGroup(defaultManagedResourceGroup, samRequestContext).unsafeRunSync()

        defaultActionManagedIdentities.map(dao.createActionManagedIdentity(_, samRequestContext).unsafeRunSync())

        val bothLoadedServiceAccounts =
          dao.getAllActionManagedIdentitiesForResource(defaultResource.fullyQualifiedId, samRequestContext).unsafeRunSync().toSet
        bothLoadedServiceAccounts should be(defaultActionManagedIdentities)

        dao.deleteAllActionManagedIdentitiesForResource(defaultResource.fullyQualifiedId, samRequestContext).unsafeRunSync()

        dao.getAllActionManagedIdentitiesForResource(defaultResource.fullyQualifiedId, samRequestContext).unsafeRunSync() should be(Seq.empty)
      }
    }

    "listParentGroups" - {
      "list all of the parent groups of a group" in {
        assume(databaseEnabled, databaseEnabledClue)
        val subGroup = defaultGroup
        val members: Set[WorkbenchSubject] = Set(subGroup.id)
        val parentGroup1 = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup1"), members, WorkbenchEmail("baz@qux.com"))
        val parentGroup2 = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup2"), members, WorkbenchEmail("bar@baz.com"))
        val grandParentGroup = BasicWorkbenchGroup(WorkbenchGroupName("grandParentGroup"), Set(parentGroup1.id), WorkbenchEmail("qux@baz.com"))

        dao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(parentGroup1, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(parentGroup2, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(grandParentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.listParentGroups(subGroup.id, samRequestContext).unsafeRunSync() should contain theSameElementsAs Set(parentGroup1.id, parentGroup2.id)
        dao.listParentGroups(parentGroup1.id, samRequestContext).unsafeRunSync() should contain theSameElementsAs Set(grandParentGroup.id)
        dao.listParentGroups(parentGroup2.id, samRequestContext).unsafeRunSync() shouldBe empty
        dao.listParentGroups(grandParentGroup.id, samRequestContext).unsafeRunSync() shouldBe empty
      }

      "return empty when group does not exist" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.listParentGroups(WorkbenchGroupName("nonexistentGroup"), samRequestContext).unsafeRunSync() shouldBe empty
      }
    }
  }
}
