/*
 * Copyright 2013 Jacob Klinker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.klinker.android.send_message;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Telephony.Sms;
import android.telephony.SmsManager;

public class SentReceiver extends BroadcastReceiver {
	@Override
	public void onReceive(Context context, Intent intent) {
		Uri outboxUri = (Uri) intent.getParcelableExtra(Transaction.SMS_OUTBOX_URI);
		if (outboxUri == null)
			return;
		switch (getResultCode()) {
		case Activity.RESULT_OK:
			Sms.moveMessageToFolder(context, outboxUri, Sms.MESSAGE_TYPE_SENT, intent.getIntExtra("errorCode", 0));
			break;
		case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
			Sms.moveMessageToFolder(context, outboxUri, Sms.MESSAGE_TYPE_FAILED, intent.getIntExtra("errorCode", 0));
			break;
		case SmsManager.RESULT_ERROR_NO_SERVICE:
		case SmsManager.RESULT_ERROR_NULL_PDU:
		case SmsManager.RESULT_ERROR_RADIO_OFF:
			Sms.moveMessageToFolder(context, outboxUri, Sms.MESSAGE_TYPE_QUEUED, intent.getIntExtra("errorCode", 0));
			break;
		}
	}
}
