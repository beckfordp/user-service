// SPDX-License-Identifier: MIT

package userservice

import cats.effect.IO
import cats.syntax.all._
import munit.CatsEffectSuite

class PasswordHasherSuite extends CatsEffectSuite {

  private val hasher: IO[PasswordHasher[IO]] =
    PasswordHasher.pbkdf2[IO](iterations = 1000)

  private val password = TestValues.password("correct-horse-battery-staple")

  test("hash returns the documented pbkdf2 format") {
    hasher.flatMap(_.hash(password)).map { stored =>
      val parts = stored.split("\\$")
      assertEquals(parts.length, 4)
      assertEquals(parts(0), "pbkdf2")
      assertEquals(parts(1), "1000")
      assert(parts(2).nonEmpty)
      assert(parts(3).nonEmpty)
      assert(!stored.contains(password.value))
    }
  }

  test("verify accepts the right password and rejects a wrong one") {
    for {
      h <- hasher
      stored <- h.hash(password)
      right <- h.verify(password, Some(stored))
      wrong <- h.verify(
        TestValues.password("wrong-horse-battery"),
        Some(stored)
      )
    } yield {
      assertEquals(right, PasswordCheck.Match)
      assertEquals(wrong, PasswordCheck.Mismatch)
    }
  }

  test("the same password hashes differently each time (random salt)") {
    for {
      h <- hasher
      first <- h.hash(password)
      second <- h.hash(password)
    } yield assertNotEquals(first, second)
  }

  test("verify reports a malformed stored value as Malformed, not Mismatch") {
    for {
      h <- hasher
      results <- List(
        "not-a-hash",
        "",
        "pbkdf2$abc$c2FsdA$aGFzaA",
        "pbkdf2$0$c2FsdA$aGFzaA",
        "pbkdf2$-5$c2FsdA$aGFzaA",
        "pbkdf2$1000$not*base64$aGFzaA",
        "bcrypt$1000$c2FsdA$aGFzaA"
      ).traverse(stored => h.verify(password, Some(stored)))
    } yield assertEquals(results.distinct, List(PasswordCheck.Malformed))
  }

  test("verify against no account runs a dummy check and is a Mismatch") {
    hasher
      .flatMap(_.verify(password, None))
      .map(result => assertEquals(result, PasswordCheck.Mismatch))
  }
}
