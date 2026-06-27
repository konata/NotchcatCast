package notch.cat.cast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerOpenPolicyTest {
  @Test
  fun missingOverlayPermissionDoesNotSuppressPlayerActivityLaunch() {
    val plan = playerOpenPlan(canPostNotification = true)

    assertTrue(plan.tryActivity)
    assertTrue(plan.notifyOnFailure)
  }

  @Test
  fun notificationFallbackDependsOnlyOnNotificationPermission() {
    val plan = playerOpenPlan(canPostNotification = false)

    assertTrue(plan.tryActivity)
    assertFalse(plan.notifyOnFailure)
  }
}
