package com.lonelytragedy.r1999trackerapp

import android.content.Intent
import android.widget.RemoteViewsService

class BannerWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return BannerWidgetFactory(applicationContext)
    }
}
