package userservice

import cats.effect.IO
import munit.CatsEffectSuite

class PasswordHasherSuite extends CatsEffectSuite {

  private val hasher = PasswordHasher(iterations = 1000)

  test("hash returns the documented pbkdf2 format") {
    hasher.hash[IO]("correct horse").map { stored =>
      val parts = stored.split("\\$")
      assertEquals(parts.length, 4)
      assertEquals(parts(0), "pbkdf2")
      assertEquals(parts(1), "1000")
      assert(parts(2).nonEmpty)
      assert(parts(3).nonEmpty)
    }
  }

  test("verify accepts the right password and rejects a wrong one") {
    for {
      stored <- hasher.hash[IO]("correct horse")
      right <- hasher.verify[IO]("correct horse", stored)
      wrong <- hasher.verify[IO]("wrong horse", stored)
    } yield {
      assert(right)
      assert(!wrong)
    }
  }

  test("the same password hashes differently each time (random salt)") {
    for {
      first <- hasher.hash[IO]("correct horse")
      second <- hasher.hash[IO]("correct horse")
    } yield assertNotEquals(first, second)
  }

  test("verify is false for a malformed stored value") {
    for {
      bad <- hasher.verify[IO]("correct horse", "not-a-hash")
      empty <- hasher.verify[IO]("correct horse", "")
    } yield {
      assert(!bad)
      assert(!empty)
    }
  }
}
