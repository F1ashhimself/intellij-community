// FIR_COMPARISON
// PSI_ELEMENT: org.jetbrains.kotlin.psi.KtObjectDeclaration
// OPTIONS: usages
class Foo {
    companion object <caret>Bar {
        fun f() {
        }
    }
}

// FIR_COMPARISON
