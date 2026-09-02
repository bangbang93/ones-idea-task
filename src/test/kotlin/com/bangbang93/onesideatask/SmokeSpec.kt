package com.bangbang93.onesideatask

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SmokeSpec : FunSpec({
    test("kotest wiring works") {
        1 + 1 shouldBe 2
    }
})
