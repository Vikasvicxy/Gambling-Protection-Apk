package dev.gamblock.protection.tamper

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Device Administrator fallback, pinned in its dormant state.
 *
 * This receiver is a contingency, not a feature: it exists so the uninstall guard has
 * somewhere to land if Play rejects the Accessibility service for good. Two things
 * therefore have to hold, and the second matters as much as the first.
 *
 * The mechanism has to be real — declared in the manifest, holding a policy that
 * actually makes Android block the uninstall path, and able to hand the user the
 * system prompt. And it has to stay inert: no code path may request administrator
 * rights on the user's behalf, and the guard's behaviour must be unchanged while the
 * accessibility service is the active mechanism. A fallback that quietly activates
 * alongside the primary would defeat the point of having a fallback the user chose.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShieldDeviceAdminTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `is registered as a device administrator receiver`() {
        val component = ShieldDeviceAdmin.componentName(context)
        val info = context.packageManager.getReceiverInfo(
            component,
            android.content.pm.PackageManager.GET_META_DATA,
        )
        assertThat(info.metaData.getInt("android.app.device_admin")).isNotEqualTo(0)
    }

    @Test
    fun `is bound to the platform permission so only the system can activate it`() {
        val component = ShieldDeviceAdmin.componentName(context)
        val info = context.packageManager.getReceiverInfo(component, 0)
        assertThat(info.permission).isEqualTo("android.permission.BIND_DEVICE_ADMIN")
    }

    @Test
    fun `declares a force-lock policy, which is what holds the uninstall path`() {
        // An administrator with no active policy does not block uninstall on modern
        // Android, so force-lock is the load-bearing line in the policy file.
        val resId = context.resources.getIdentifier(
            "shield_device_admin_policies",
            "xml",
            context.packageName,
        )
        check(resId != 0) { "shield_device_admin_policies.xml not found in the merged manifest" }
        val policies = context.resources.getXml(resId)
        val names = buildList {
            var event = policies.next()
            while (event != android.content.res.XmlResourceParser.END_DOCUMENT) {
                if (event == android.content.res.XmlResourceParser.START_TAG) add(policies.name)
                event = policies.next()
            }
        }
        assertThat(names).contains("uses-policies")
        assertThat(names).contains("force-lock")
    }

    @Test
    fun `requests no wipe or password policy`() {
        // Each of these is separately Play-restricted and none of them helps hold an
        // uninstall in place. Declaring one to get the admin right would cost real
        // user trust for no protection gained.
        val resId = context.resources.getIdentifier(
            "shield_device_admin_policies",
            "xml",
            context.packageName,
        )
        val raw = context.resources.getXml(resId)
        buildList {
            var event = raw.next()
            while (event != android.content.res.XmlResourceParser.END_DOCUMENT) {
                if (event == android.content.res.XmlResourceParser.START_TAG) add(raw.name)
                event = raw.next()
            }
        }.let { declared ->
            assertThat(declared).doesNotContain("wipe-data")
            assertThat(declared).doesNotContain("reset-password")
            assertThat(declared).doesNotContain("disable-camera")
        }
    }

    @Test
    fun `is not active until the user grants it`() {
        // The whole point of the dormant state: out of the box Shield holds no
        // administrator rights at all.
        assertThat(ShieldDeviceAdmin.isActive(context)).isFalse()
    }

    @Test
    fun `hands over the system activation prompt rather than activating silently`() {
        val intent = ShieldDeviceAdmin.activationIntent(context)
        assertThat(intent.action).isEqualTo(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        val extra = intent.extras?.getParcelable<ComponentName>(
            DevicePolicyManager.EXTRA_DEVICE_ADMIN,
        )
        assertThat(extra).isEqualTo(ShieldDeviceAdmin.componentName(context))
    }

    @Test
    fun `can hand the rights back`() {
        // An admin that cannot be released is a device the user cannot uninstall from,
        // which is precisely the stranding this app exists to avoid. There is no public
        // deactivate-request action, so the reliable path is removeActiveAdmin, which
        // reports honestly whether it actually did anything.
        assertThat(ShieldDeviceAdmin.deactivate(context)).isFalse()
    }

    @Test
    fun `exposes a user-driven revocation screen`() {
        // The counterpart to deactivate: revocation should be something the user can do
        // themselves. The action string is undocumented AOSP, so this is best-effort by
        // design and the test pins only that an intent is produced.
        val intent = ShieldDeviceAdmin.deactivationIntent(context)
        assertThat(intent.action).isNotEmpty()
        assertThat(intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    }

    @Test
    fun `does not change what the accessibility guard does`() {
        // The fallback must be inert. While the accessibility service is enabled, the
        // device admin must not be involved, or enabling one would silently require
        // the other.
        val service = ComponentName(context, ShieldAccessibilityGuard::class.java)
        val info = context.packageManager.getServiceInfo(service, 0)
        assertThat(info.permission).isEqualTo("android.permission.BIND_ACCESSIBILITY_SERVICE")
        assertThat(ShieldDeviceAdmin.isActive(context)).isFalse()
    }
}