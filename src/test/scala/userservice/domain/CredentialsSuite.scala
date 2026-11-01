package userservice.domain

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.util.Locale

class CredentialsSuite extends ScalaCheckSuite {

  private val localPart = Gen.nonEmptyListOf(Gen.alphaNumChar).map(_.mkString)
  private val validEmail: Gen[String] =
    for {
      user <- localPart
      host <- localPart
      tld <- Gen.nonEmptyListOf(Gen.alphaLowerChar).map(_.mkString)
    } yield s"$user@$host.$tld"

  test("Email.parse trims and lower-cases") {
    assertEquals(
      Email.parse("  Ada@Example.COM ").map(Email.render),
      Right("ada@example.com")
    )
  }

  test("Email.parse rejects a malformed address") {
    List("not-an-email", "a@b", "a b@example.com", "", "@example.com")
      .foreach(raw =>
        assertEquals(Email.parse(raw), Left(AuthError.InvalidEmail))
      )
  }

  test("Email.parse lower-cases independently of the default locale") {
    val original = Locale.getDefault
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"))
      assertEquals(
        Email.parse("ADMIN@EXAMPLE.COM").map(Email.render),
        Right("admin@example.com")
      )
    } finally Locale.setDefault(original)
  }

  property("Email.parse is idempotent over its own rendering") {
    forAll(validEmail) { raw =>
      val parsed = Email.parse(raw)
      assert(parsed.isRight)
      assertEquals(parsed.flatMap(e => Email.parse(Email.render(e))), parsed)
    }
  }

  property("Email.parse ignores surrounding whitespace and case") {
    forAll(validEmail) { raw =>
      assertEquals(
        Email.parse(s"  ${raw.toUpperCase(Locale.ROOT)}\t"),
        Email.parse(raw)
      )
    }
  }

  property("signup passwords shorter than 8 characters are rejected") {
    forAll(
      Gen.choose(0, 7).flatMap(n => Gen.listOfN(n, Gen.asciiPrintableChar))
    ) { chars =>
      assertEquals(
        Password.forSignup(chars.mkString).left.map(identity),
        Left(AuthError.PasswordTooShort)
      )
    }
  }

  property("signup passwords of 8+ characters are accepted unchanged") {
    forAll(
      Gen.choose(8, 64).flatMap(n => Gen.listOfN(n, Gen.asciiPrintableChar))
    ) { chars =>
      assertEquals(
        Password.forSignup(chars.mkString).map(_.value),
        Right(chars.mkString)
      )
    }
  }

  test("a login password must be non-empty") {
    assertEquals(Password.forLogin(""), Left(AuthError.PasswordMissing))
    assertEquals(Password.forLogin("x").map(_.value), Right("x"))
  }

  test("a Password never renders its value") {
    val rendered =
      Password.forLogin("correct-horse-battery-staple").map(_.toString)
    assertEquals(rendered, Right("Password(<redacted>)"))
  }

  test("parseSignup checks the email before the password") {
    assertEquals(
      Credentials.parseSignup("nope", "short").map(_.email),
      Left(AuthError.InvalidEmail)
    )
    assertEquals(
      Credentials.parseSignup("ada@example.com", "short").map(_.email),
      Left(AuthError.PasswordTooShort)
    )
    assertEquals(
      Credentials
        .parseSignup(" ADA@example.com", "correct horse")
        .map(c => Email.render(c.email)),
      Right("ada@example.com")
    )
  }

  test("parseLogin checks the email, then a non-empty password") {
    assertEquals(
      Credentials.parseLogin("nope", "").map(_.email),
      Left(AuthError.InvalidEmail)
    )
    assertEquals(
      Credentials.parseLogin("ada@example.com", "").map(_.email),
      Left(AuthError.PasswordMissing)
    )
    assert(Credentials.parseLogin("ada@example.com", "x").isRight)
  }

  test("TokenKind wire values match the auth_tokens.kind CHECK constraint") {
    assertEquals(
      List(TokenKind.Access, TokenKind.Refresh).map(_.wire),
      List("access", "refresh")
    )
  }
}
