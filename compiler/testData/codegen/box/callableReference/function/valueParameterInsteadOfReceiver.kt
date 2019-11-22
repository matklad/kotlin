class Z

class Q {
  fun f(z: Z) = "OK"
}

fun box(): String {
    return Z().run(Q()::f)
}

