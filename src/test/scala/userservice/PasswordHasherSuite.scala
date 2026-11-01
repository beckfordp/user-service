package userservice

import cats.effect.IO
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
      right <- h.verify(password, stored)
      wrong <- h.verify(TestValues.password("wrong-horse-battery"), stored)
    } yield {
      assert(right)
      assert(!wrong)
    }
  }

  test("the same password hashes differently each time (random salt)") {
    for {
      h <- hasher
      first <- h.hash(password)
      second <- h.hash(password)
    } yield assertNotEquals(first, second)
  }

  test("verify is false for a malformed stored value") {
    for {
      h <- hasher
      bad <- h.verify(password, "not-a-hash")
      empty <- h.verify(password, "")
    } yield {
      assert(!bad)
      assert(!empty)
    }
  }
}
