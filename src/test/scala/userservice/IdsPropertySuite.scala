package userservice

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import userservice.domain.{AccountId, TokenId}

import java.util.UUID

class IdsPropertySuite extends ScalaCheckSuite {

  property("AccountId renders a canonical UUID string") {
    forAll(Gen.uuid) { uuid =>
      assertEquals(
        UUID.fromString(AccountId.render(AccountId.fromUuid(uuid))),
        uuid
      )
    }
  }

  property("TokenId renders a canonical UUID string") {
    forAll(Gen.uuid) { uuid =>
      assertEquals(
        UUID.fromString(TokenId.render(TokenId.fromUuid(uuid))),
        uuid
      )
    }
  }
}
