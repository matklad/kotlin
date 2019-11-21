// FILE: Dummy.java

public class Dummy {

}

// FILE: test.kt

private fun String.isRangeVersion(): Boolean = true

class User : Dummy() {
    fun test(s: String?) {
        if (s != null) {
            s.isRangeVersion()
        }
    }
}