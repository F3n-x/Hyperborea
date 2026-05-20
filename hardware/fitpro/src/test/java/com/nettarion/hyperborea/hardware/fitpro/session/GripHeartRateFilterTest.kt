package com.nettarion.hyperborea.hardware.fitpro.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GripHeartRateFilterTest {

    @Test
    fun `first valid sample passes through unchanged`() {
        val filter = GripHeartRateFilter()
        assertThat(filter.update(120)).isEqualTo(120)
    }

    @Test
    fun `zero is treated as no contact and returns null`() {
        val filter = GripHeartRateFilter()
        assertThat(filter.update(0)).isNull()
    }

    @Test
    fun `implausible high and low samples are rejected`() {
        val filter = GripHeartRateFilter()
        assertThat(filter.update(20)).isNull()   // below resting-low bound
        assertThat(filter.update(255)).isNull()  // above athletic-max bound
    }

    @Test
    fun `smoothing damps a jump rather than tracking it instantly`() {
        val filter = GripHeartRateFilter(smoothing = 0.3f)
        filter.update(100)
        // A jump to 150 should land between the old value and the new sample, not at 150.
        val smoothed = filter.update(150)!!
        assertThat(smoothed).isGreaterThan(100)
        assertThat(smoothed).isLessThan(150)
        assertThat(smoothed).isEqualTo(115) // 100 + 0.3 * (150 - 100)
    }

    @Test
    fun `contact loss resets the average so re-gripping starts clean`() {
        val filter = GripHeartRateFilter(smoothing = 0.3f)
        filter.update(100)
        assertThat(filter.update(0)).isNull()      // contact lost — forget the average
        // Re-grip: the next valid sample passes through directly, not blended with the stale 100.
        assertThat(filter.update(140)).isEqualTo(140)
    }

    @Test
    fun `a single out-of-range spike does not corrupt the running average`() {
        val filter = GripHeartRateFilter(smoothing = 0.3f)
        filter.update(120)
        filter.update(300)                          // spike rejected, average forgotten
        assertThat(filter.update(122)).isEqualTo(122) // resumes cleanly
    }
}
