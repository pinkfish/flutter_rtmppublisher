package com.whelksoft.yasea

import android.os.Handler
import android.os.Message
import java.io.IOException
import java.lang.ref.WeakReference

/**
 * Created by leo.ma on 2016/11/4.
 */
class SrsRecordHandler constructor(listener: SrsRecordListener) : Handler() {
    private val mWeakListener: WeakReference<SrsRecordListener>
    fun notifyRecordPause() {
        sendEmptyMessage(MSG_RECORD_PAUSE)
    }

    fun notifyRecordResume() {
        sendEmptyMessage(MSG_RECORD_RESUME)
    }

    fun notifyRecordStarted(msg: String?) {
        obtainMessage(MSG_RECORD_STARTED, msg).sendToTarget()
    }

    fun notifyRecordFinished(msg: String?) {
        obtainMessage(MSG_RECORD_FINISHED, msg).sendToTarget()
    }

    fun notifyRecordIllegalArgumentException(e: IllegalArgumentException?) {
        obtainMessage(MSG_RECORD_ILLEGEL_ARGUMENT_EXCEPTION, e).sendToTarget()
    }

    fun notifyRecordIOException(e: IOException?) {
        obtainMessage(MSG_RECORD_IO_EXCEPTION, e).sendToTarget()
    }

    // runs on UI thread
    public override fun handleMessage(msg: Message) {
        val listener: SrsRecordListener? = mWeakListener.get()
        if (listener == null) {
            return
        }
        when (msg.what) {
            MSG_RECORD_PAUSE -> listener.onRecordPause()
            MSG_RECORD_RESUME -> listener.onRecordResume()
            MSG_RECORD_STARTED -> listener.onRecordStarted(msg.obj as String?)
            MSG_RECORD_FINISHED -> listener.onRecordFinished(msg.obj as String?)
            MSG_RECORD_ILLEGEL_ARGUMENT_EXCEPTION -> listener.onRecordIllegalArgumentException(msg.obj as IllegalArgumentException?)
            MSG_RECORD_IO_EXCEPTION -> listener.onRecordIOException(msg.obj as IOException?)
            else -> throw RuntimeException("unknown msg " + msg.what)
        }
    }

    open interface SrsRecordListener {
        fun onRecordPause()
        fun onRecordResume()
        fun onRecordStarted(msg: String?)
        fun onRecordFinished(msg: String?)
        fun onRecordIllegalArgumentException(e: IllegalArgumentException?)
        fun onRecordIOException(e: IOException?)
    }

    companion object {
        private val MSG_RECORD_PAUSE: Int = 0
        private val MSG_RECORD_RESUME: Int = 1
        private val MSG_RECORD_STARTED: Int = 2
        private val MSG_RECORD_FINISHED: Int = 3
        private val MSG_RECORD_ILLEGEL_ARGUMENT_EXCEPTION: Int = 4
        private val MSG_RECORD_IO_EXCEPTION: Int = 5
    }

    init {
        mWeakListener = WeakReference(listener)
    }
}