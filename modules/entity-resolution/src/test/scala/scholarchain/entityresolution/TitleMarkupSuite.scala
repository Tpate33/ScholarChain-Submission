package scholarchain.entityresolution

class TitleMarkupSuite extends munit.FunSuite {
  import TitleMarkup.strip

  test("HTML, JATS, and MathML tags are removed") {
    assertEquals(strip("Growth of <i>E. coli</i> in <b>vitro</b>"), "Growth of E. coli in vitro")
    assertEquals(strip("CO<jats:sub>2</jats:sub> capture"), "CO 2 capture")
    assertEquals(strip("<mml:math><mml:mi>x</mml:mi></mml:math>-ray<br/>imaging"), "x -ray imaging")
  }

  test("character references are decoded; invalid ones are kept") {
    assertEquals(strip("Search &amp; Rescue"), "Search & Rescue")
    assertEquals(strip("&#946;-lactam and &#x3B1;-helix"), "β-lactam and α-helix")
    assertEquals(strip("bad &#xD800; ref"), "bad &#xD800; ref")
  }

  test("inline LaTeX commands and delimiters are removed, arguments kept") {
    assertEquals(
      strip("The $\\beta$-model and \\emph{fast} sorting"),
      "The -model and fast sorting"
    )
  }

  test("text that only resembles markup is kept") {
    assertEquals(strip("When a < b and c > d"), "When a < b and c > d")
    assertEquals(strip(null), "")
  }

  test("v2 is unchanged: TitleText does not strip markup (wiring needs er-features.v3)") {
    assertEquals(TitleText.normalize("<i>E. coli</i> growth"), "i e coli i growth")
    assertEquals(TitleText.normalize(strip("<i>E. coli</i> growth")), "e coli growth")
  }
}
