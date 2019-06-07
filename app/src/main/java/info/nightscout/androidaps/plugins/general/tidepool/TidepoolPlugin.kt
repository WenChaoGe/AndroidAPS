package info.nightscout.androidaps.plugins.general.tidepool

import android.text.Html
import info.nightscout.androidaps.Constants
import info.nightscout.androidaps.MainApp
import info.nightscout.androidaps.R
import info.nightscout.androidaps.RxBus
import info.nightscout.androidaps.events.EventNetworkChange
import info.nightscout.androidaps.events.EventNewBG
import info.nightscout.androidaps.events.EventPreferenceChange
import info.nightscout.androidaps.interfaces.PluginBase
import info.nightscout.androidaps.interfaces.PluginDescription
import info.nightscout.androidaps.interfaces.PluginType
import info.nightscout.androidaps.logging.L
import info.nightscout.androidaps.plugins.general.tidepool.comm.TidepoolUploader
import info.nightscout.androidaps.plugins.general.tidepool.events.EventTidepoolDoUpload
import info.nightscout.androidaps.plugins.general.tidepool.events.EventTidepoolResetData
import info.nightscout.androidaps.plugins.general.tidepool.events.EventTidepoolStatus
import info.nightscout.androidaps.plugins.general.tidepool.events.EventTidepoolUpdateGUI
import info.nightscout.androidaps.plugins.general.tidepool.utils.RateLimit
import info.nightscout.androidaps.receivers.ChargingStateReceiver
import info.nightscout.androidaps.receivers.NetworkChangeReceiver
import info.nightscout.androidaps.utils.SP
import info.nightscout.androidaps.utils.T
import info.nightscout.androidaps.utils.ToastUtils
import io.reactivex.disposables.Disposable
import org.slf4j.LoggerFactory
import java.util.*

object TidepoolPlugin : PluginBase(PluginDescription()
        .mainType(PluginType.GENERAL)
        .pluginName(R.string.tidepool)
        .shortName(R.string.tidepool_shortname)
        .fragmentClass(TidepoolJavaFragment::class.java.name)
        .preferencesId(R.xml.pref_tidepool)
        .description(R.string.description_tidepool)
) {
    private val log = LoggerFactory.getLogger(L.TIDEPOOL)
    private var disposable: Disposable? = null

    private val listLog = ArrayList<EventTidepoolStatus>()
    @Suppress("DEPRECATION") // API level 24 to replace call
    var textLog = Html.fromHtml("")

    override fun onStart() {
        disposable = RxBus
                .toObservable()
                .subscribe { event: Any -> onEvent(event) }
        super.onStart()
    }

    override fun onStop() {
        disposable?.dispose()
        super.onStop()
    }

    fun doUpload() {
        if (TidepoolUploader.connectionStatus == TidepoolUploader.ConnectionStatus.DISCONNECTED)
            TidepoolUploader.doLogin(true)
        else
            TidepoolUploader.doUpload()
    }

    private fun onEvent(event: Any) {
        when (event) {
            is EventTidepoolDoUpload -> doUpload()
            is EventTidepoolResetData -> {
                if (TidepoolUploader.connectionStatus != TidepoolUploader.ConnectionStatus.CONNECTED) {
                    log.debug("Not connected for deleteDataset")
                    return
                }
                TidepoolUploader.deleteDataSet()
                SP.putLong(R.string.key_tidepool_last_end, 0)
                TidepoolUploader.doLogin()
            }
            is EventTidepoolStatus -> addToLog(event)
            is EventNewBG -> {
                if (event.bgReading!!.date!! < TidepoolUploader.getLastEnd())
                    TidepoolUploader.setLastEnd(event.bgReading!!.date!!)
                if (isEnabled(PluginType.GENERAL)
                        && (!SP.getBoolean(R.string.key_tidepool_only_while_charging, false) || ChargingStateReceiver.isCharging())
                        && (!SP.getBoolean(R.string.key_tidepool_only_while_unmetered, false) || NetworkChangeReceiver.isWifiConnected())
                        && RateLimit.ratelimit("tidepool-new-data-upload", T.mins(4).secs().toInt()))
                    doUpload()
            }
            is EventPreferenceChange -> {
                if (event.isChanged(R.string.key_tidepool_dev_servers)
                        || event.isChanged(R.string.key_tidepool_username)
                        || event.isChanged(R.string.key_tidepool_password)
                )
                    TidepoolUploader.resetInstance()
            }
            is EventNetworkChange -> {
            } // TODO start upload on wifi connect
        }
    }

    @Synchronized
    private fun addToLog(ev: EventTidepoolStatus) {
        synchronized(listLog) {
            listLog.add(ev)
            // remove the first line if log is too large
            if (listLog.size >= Constants.MAX_LOG_LINES) {
                listLog.removeAt(0)
            }
        }
        MainApp.bus().post(EventTidepoolUpdateGUI())
    }

    @Synchronized
    fun updateLog() {
        try {
            val newTextLog = StringBuilder()
            synchronized(listLog) {
                for (log in listLog) {
                    newTextLog.append(log.toPreparedHtml())
                }
            }
            @Suppress("DEPRECATION") // API level 24 to replace call
            textLog = Html.fromHtml(newTextLog.toString())
        } catch (e: OutOfMemoryError) {
            ToastUtils.showToastInUiThread(MainApp.instance().applicationContext, "Out of memory!\nStop using this phone !!!", R.raw.error)
        }
    }

}