package com.calldetector.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** FFT radix-2 iterativa, in-place. `size` precisa ser potência de 2. Sem alocação por chamada. */
class Fft(val size: Int) {
    private val cosTable = FloatArray(size / 2)
    private val sinTable = FloatArray(size / 2)
    private val bitRev = IntArray(size)

    init {
        require(size >= 2 && (size and (size - 1)) == 0) { "size precisa ser potência de 2" }
        for (i in 0 until size / 2) {
            val a = -2.0 * PI * i / size
            cosTable[i] = cos(a).toFloat()
            sinTable[i] = sin(a).toFloat()
        }
        val bits = size.countTrailingZeroBits()
        for (i in 0 until size) {
            var r = 0
            var x = i
            for (b in 0 until bits) {
                r = (r shl 1) or (x and 1)
                x = x shr 1
            }
            bitRev[i] = r
        }
    }

    fun transform(re: FloatArray, im: FloatArray) {
        val n = size
        for (i in 0 until n) {
            val j = bitRev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr
                    im[j + half] = im[j] - xi
                    re[j] += xr
                    im[j] += xi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}
