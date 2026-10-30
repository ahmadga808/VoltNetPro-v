package com.subsmgr.app

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemePref.load(this)
        val db = Db(applicationContext)
        if (AutoBackup.dirUri(this) != null) AutoBackup.schedule(this)
        DailyReminder.schedule(this)
        Router.schedule(this)
        AutoSms.schedule(this)
        Router.scheduleMonitor(this)
        Cloud.migrate(this)
        Thread { try { Cloud.fetchConfig(applicationContext) } catch (e: Exception) { } }.start()
        Router.migrate(this)
        Sas.migrate(this)
        Airos.schedule(this)
        Vpn.autoStart(applicationContext)
        DailyReminder.askPermission(this)
        if (Cloud.loggedIn(this)) {
            Cloud.scheduleAuto(this)
            Thread { try { Cloud.fetchLicense(applicationContext) } catch (e: Exception) { } }.start()
        }
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AppRoot(db) { finish() } }
                }
            }
        }
    }
}
