package notch.cat.cast

import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorLayoutTest {
  @Test
  fun portraitMirrorFitsInsideLandscapeDisplayWithoutStretching() {
    assertEquals(MirrorFit(width = 498, height = 1080), mirrorFit(1920, 1080, 498, 1080))
  }

  @Test
  fun landscapeMirrorUsesFullLandscapeDisplay() {
    assertEquals(MirrorFit(width = 1920, height = 1080), mirrorFit(1920, 1080, 1920, 1080))
  }

  @Test
  fun invalidMirrorSizeFallsBackToContainer() {
    assertEquals(MirrorFit(width = 1920, height = 1080), mirrorFit(1920, 1080, 0, 1080))
  }
}
