package org.broadinstitute.dsde.workbench.sam.openam

import java.util.UUID

import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.Generator._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.TestSupport._
import org.broadinstitute.dsde.workbench.sam.directory._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.scalatest._

import scala.concurrent.ExecutionContext.Implicits.global
/**
  * Created by dvoet on 6/26/17.
  */
class LdapAccessPolicyDAOSpec extends FlatSpec with Matchers with TestSupport with BeforeAndAfter with BeforeAndAfterAll {
  private val connectionPool = new LDAPConnectionPool(new LDAPConnection(dirURI.getHost, dirURI.getPort, directoryConfig.user, directoryConfig.password), directoryConfig.connectionPoolSize)
  val dao = new LdapAccessPolicyDAO(connectionPool, directoryConfig)
  val dirDao = new LdapDirectoryDAO(connectionPool, directoryConfig)
  val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    runAndWait(schemaDao.init())
  }

  before {
    runAndWait(schemaDao.clearDatabase())
    runAndWait(schemaDao.createOrgUnits())
  }

  def toEmail(resourceType: String, resourceId: String, policyName: String) = {
    WorkbenchEmail(s"policy-$resourceType-$resourceId-$policyName@dev.test.firecloud.org")
  }

  "LdapAccessPolicyDAO" should "create, list, delete policies" in {
    val typeName1 = ResourceTypeName(UUID.randomUUID().toString)
    val typeName2 = ResourceTypeName(UUID.randomUUID().toString)

    val policy1Group = BasicWorkbenchGroup(WorkbenchGroupName("role1-a"), Set(WorkbenchUserId("foo")), toEmail(typeName1.value, "resource", "role1-a"))
    val policy2Group = BasicWorkbenchGroup(WorkbenchGroupName("role1-b"), Set(WorkbenchUserId("foo")),toEmail(typeName1.value, "resource", "role1-b"))
    val policy3Group = BasicWorkbenchGroup(WorkbenchGroupName("role1-a"), Set(WorkbenchUserId("foo")), toEmail(typeName2.value, "resource", "role1-a"))

    val policy1 = AccessPolicy(ResourceAndPolicyName(Resource(typeName1, ResourceId("resource")), AccessPolicyName("role1-a")), policy1Group.members, policy1Group.email, Set(ResourceRoleName("role1")), Set(ResourceAction("action1"), ResourceAction("action2")))
    val policy2 = AccessPolicy(ResourceAndPolicyName(Resource(typeName1, ResourceId("resource")), AccessPolicyName("role1-b")), policy2Group.members, policy2Group.email, Set(ResourceRoleName("role1")), Set(ResourceAction("action3"), ResourceAction("action4")))
    val policy3 = AccessPolicy(ResourceAndPolicyName(Resource(typeName2, ResourceId("resource")), AccessPolicyName("role1-a")), policy3Group.members, policy3Group.email, Set(ResourceRoleName("role1")), Set(ResourceAction("action1"), ResourceAction("action2")))

    dao.createResourceType(typeName1).unsafeRunSync()
    dao.createResourceType(typeName2).unsafeRunSync()

    dao.createResource(policy1.id.resource).unsafeRunSync()
    //policy2's resource already exists
    dao.createResource(policy3.id.resource).unsafeRunSync()

    assertResult(Seq.empty) {
      runAndWait(dao.listAccessPolicies(policy1.id.resource)).toSeq
      runAndWait(dao.listAccessPolicies(policy2.id.resource)).toSeq
      runAndWait(dao.listAccessPolicies(policy3.id.resource)).toSeq
    }

    runAndWait(dao.createPolicy(policy1))
    runAndWait(dao.createPolicy(policy2))
    runAndWait(dao.createPolicy(policy3))

    assertResult(Seq(policy1, policy2)) {
      runAndWait(dao.listAccessPolicies(policy1.id.resource)).toSeq
    }

    assertResult(Seq(policy3)) {
      runAndWait(dao.listAccessPolicies(policy3.id.resource)).toSeq
    }

    runAndWait(dao.deletePolicy(policy1))

    assertResult(Seq(policy2)) {
      runAndWait(dao.listAccessPolicies(policy1.id.resource)).toSeq
    }

    runAndWait(dao.deletePolicy(policy2))

    assertResult(Seq.empty) {
      runAndWait(dao.listAccessPolicies(policy1.id.resource)).toSeq
    }

    runAndWait(dao.deletePolicy(policy3))
  }

  "LdapAccessPolicyDAO listUserPolicyResponse" should "return UserPolicyResponse" in {
    val policy = genPolicy.sample.get
    val res = for{
      _ <- dao.createResourceType(policy.id.resource.resourceTypeName)
      _ <- dao.createResourceType(policy.id.resource.resourceTypeName)
      _ <- dao.createResource(policy.id.resource)
      r <- dao.listResourceWithAuthdomains(policy.id.resource.resourceTypeName, Set(policy.id.resource.resourceId))
    } yield r

    res.unsafeRunSync() shouldBe(Set(Resource(policy.id.resource.resourceTypeName, policy.id.resource.resourceId, policy.id.resource.authDomain)))
  }
}