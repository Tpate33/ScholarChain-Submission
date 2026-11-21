package scholarchain.storage

import java.nio.file.{Files, Path}

import scholarchain.storage.local.LocalRunSource
import scholarchain.storage.model.{Bytes, SourceBounds, StoreError}
import Fixtures.*

/** The local RunSource: never follows links, refuses special names and enforces its bounds. */
class LocalRunSourceSuite extends munit.FunSuite {

  private def bounds(files: Int = 100, bytes: Long = 1024L * 1024, depth: Int = 3) =
    SourceBounds.of(files, bytes, depth).toOption.get

  private def tree(root: Path): Unit = {
    Files.createDirectories(root.resolve("a/b"))
    write(root.resolve("z.txt"), "z\n")
    write(root.resolve("a/x.txt"), "x\n")
    write(root.resolve("a/b/y.txt"), "yy\n")
  }

  private def refused(r: Either[StoreError, ?], fragment: String): Unit =
    r match {
      case Left(StoreError.Refused(d)) => assert(d.contains(fragment), d)
      case other => fail(s"expected Refused containing '$fragment', got $other")
    }

  test("bounds are constructor values and must be positive") {
    assert(SourceBounds.of(0, 1, 1).isLeft)
    assert(SourceBounds.of(1, 0, 1).isLeft)
    assert(SourceBounds.of(1, 1, 0).isLeft)
    assert(SourceBounds.of(1, 1, 1).isRight)
  }

  test("lists regular files recursively, relative and ascending by UTF-8, and reads them") {
    withTemp { root =>
      tree(root)
      val s = source(root, bounds())
      assertEquals(s.listFiles(), Right(Vector("a/b/y.txt", "a/x.txt", "z.txt")))
      assertEquals(s.read("a/b/y.txt"), Right(Bytes.utf8("yy\n")))
    }
  }

  test("a symbolic-link file is refused at listing and at read") {
    withTemp { root =>
      tree(root)
      Files.createSymbolicLink(root.resolve("a/link.txt"), root.resolve("z.txt"))
      val s = source(root, bounds())
      refused(s.listFiles(), "a/link.txt: a symbolic link is never followed")
      refused(s.read("a/link.txt"), "symbolic link")
    }
  }

  test("a symbolic-link directory is refused at listing and is not traversed by read") {
    withTemp { root =>
      tree(root)
      Files.createSymbolicLink(root.resolve("c"), root.resolve("a"))
      val s = source(root, bounds())
      refused(s.listFiles(), "c: a symbolic link is never followed")
      refused(s.read("c/x.txt"), "c: not a directory")
    }
  }

  test("a symbolic-link root is refused") {
    withTemp { tmp =>
      val real = tmp.resolve("real")
      Files.createDirectories(real)
      Files.createSymbolicLink(tmp.resolve("alias"), real)
      refused(LocalRunSource.open(tmp.resolve("alias"), bounds()), "symbolic link")
      refused(LocalRunSource.open(tmp.resolve("missing"), bounds()), "does not exist")
    }
  }

  test("maxFiles, maxTotalBytes and maxDepth are enforced before content is returned") {
    withTemp { root =>
      tree(root)
      refused(source(root, bounds(files = 2)).listFiles(), "more than maxFiles 2")
      refused(source(root, bounds(bytes = 6)).listFiles(), "more than maxTotalBytes 6")
      refused(source(root, bounds(depth = 2)).listFiles(), "deeper than maxDepth 2")
      refused(source(root, bounds(bytes = 2)).read("a/b/y.txt"), "more than maxTotalBytes 2")
    }
  }

  test("read refuses a path deeper than maxDepth without a listing (n2)") {
    withTemp { root =>
      tree(root)
      val s = source(root, bounds(depth = 2))
      refused(s.read("a/b/y.txt"), "a/b/y.txt: deeper than maxDepth 2")
      assertEquals(s.read("a/x.txt"), Right(Bytes.utf8("x\n")), "within the depth, no listing")
      assertEquals(source(root, bounds(depth = 3)).read("a/b/y.txt"), Right(Bytes.utf8("yy\n")))
    }
  }

  test("paths outside the part-manifest rules are refused, never resolved") {
    withTemp { root =>
      tree(root)
      val s = source(root, bounds())
      Vector("../z.txt", "/etc/hosts", "a//x.txt", "a/./x.txt", "a\\x.txt", "").foreach { p =>
        assert(s.read(p).isLeft, p)
      }
      write(root.resolve("bad name.txt"), "x")
      refused(s.listFiles(), "bad name.txt: file name")
      refused(s.read("missing.txt"), "missing.txt: missing")
    }
  }
}
