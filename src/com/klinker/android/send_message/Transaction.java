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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Calendar;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.provider.Telephony;
import android.provider.Telephony.Sms;
import android.telephony.SmsManager;

import com.android.mms.dom.smil.parser.SmilXmlSerializer;
import com.android.mms.transaction.HttpUtils;
import com.android.mms.transaction.TransactionSettings;
import com.google.android.mms.ContentType;
import com.google.android.mms.MMSPart;
import com.google.android.mms.MmsException;
import com.google.android.mms.pdu_alt.CharacterSets;
import com.google.android.mms.pdu_alt.EncodedStringValue;
import com.google.android.mms.pdu_alt.PduBody;
import com.google.android.mms.pdu_alt.PduComposer;
import com.google.android.mms.pdu_alt.PduPart;
import com.google.android.mms.pdu_alt.PduPersister;
import com.google.android.mms.pdu_alt.SendReq;
import com.google.android.mms.smil.SmilHelper;

public class Transaction {

	private Settings mSettings;
	private Context mContext;

	public String SMS_SENT = ".SMS_SENT";
	public String SMS_DELIVERED = ".SMS_DELIVERED";
	public static final String SMS_OUTBOX_URI = "SMS_OUTBOX_URI";

	public static final long NO_THREAD_ID = 0;

	public Transaction(Context context) {
		this(context, new Settings());
	}

	public Transaction(Context context, Settings settings) {
		mSettings = settings;
		mContext = context;

		SMS_SENT = context.getPackageName() + SMS_SENT;
		SMS_DELIVERED = context.getPackageName() + SMS_DELIVERED;
	}

	public Uri sendNewMessage(Message message, long threadId) throws SocketException, IOException, MmsException {
		if (checkMMS(message)) {
			return sendMmsMessage(message.getText(), message.getAddresses(), message.getImages(), message.getMedia(), message.getMediaMimeType(), message.getSubject());
		} else {
			return sendSmsMessage(message.getText(), message.getAddresses()[0]);
		}
	}

	private Uri sendSmsMessage(String text, String address) {
		// add signature to original text to be saved in database (does not strip unicode for saving though)
		if (!mSettings.getSignature().equals(""))
			text += "\n" + mSettings.getSignature();

		Calendar cal = Calendar.getInstance();

		long threadId = Utils.getOrCreateThreadId(mContext, address);
		Uri smsOutboxUri = Sms.Outbox.addMessage(mContext.getContentResolver(), address, mSettings.getStripUnicode() ? StripAccents.stripAccents(text) : text, "", cal.getTimeInMillis(), mSettings.getDeliveryReports(), threadId);
		
		PendingIntent sentPI = PendingIntent.getBroadcast(mContext, 0, new Intent(SMS_SENT).putExtra(SMS_OUTBOX_URI, smsOutboxUri), 0);
		PendingIntent deliveredPI = PendingIntent.getBroadcast(mContext, 0, new Intent(SMS_DELIVERED).putExtra(SMS_OUTBOX_URI, smsOutboxUri), 0);

		ArrayList<PendingIntent> sPI = new ArrayList<PendingIntent>();
		ArrayList<PendingIntent> dPI = new ArrayList<PendingIntent>();

		// edit the body of the text if unicode needs to be stripped or signature needs to be added
		if (mSettings.getStripUnicode())
			text = StripAccents.stripAccents(text);

		if (!mSettings.getPreText().equals(""))
			text = mSettings.getPreText() + " " + text;

		SmsManager smsManager = SmsManager.getDefault();
	
		ArrayList<String> parts = smsManager.divideMessage(text);

		for (int j = 0; j < parts.size(); j++) {
			sPI.add(sentPI);
			dPI.add(mSettings.getDeliveryReports() ? deliveredPI : null);
		}
		
		if (mSettings.getSplit()) {
			for (String part : parts)
				smsManager.sendTextMessage(address, null, part, sPI.get(0), dPI.get(0));
		} else {
			smsManager.sendMultipartTextMessage(address, null, parts, sPI, dPI);
		}
		return smsOutboxUri;
	}

	private Uri sendMmsMessage(String text, String[] addresses, Bitmap[] image, byte[] media, String mimeType, String subject) throws SocketException, IOException, MmsException {
		// merge the string[] of addresses into a single string so they can be inserted into the database easier
		String address = "";

		for (int i = 0; i < addresses.length; i++)
			address += addresses[i] + " ";

		address = address.trim();

		// create the parts to send
		ArrayList<MMSPart> data = new ArrayList<MMSPart>();

		for (int i = 0; i < image.length; i++) {
			// turn bitmap into byte array to be stored
			byte[] imageBytes = Message.bitmapToByteArray(image[i]);

			MMSPart part = new MMSPart();
			part.MimeType = "image/jpeg";
			part.Name = "image" + i;
			part.Data = imageBytes;
			data.add(part);
		}

		// add any extra media according to their mimeType set in the message
		// eg. videos, audio, contact cards, location maybe?
		if (media.length > 0 && mimeType != null) {
			MMSPart part = new MMSPart();
			part.MimeType = mimeType;
			part.Name = mimeType.split("/")[0];
			part.Data = media;
			data.add(part);
		}

		if (!text.equals("")) {
			// add text to the end of the part and send
			MMSPart part = new MMSPart();
			part.Name = "text";
			part.MimeType = "text/plain";
			part.Data = text.getBytes();
			data.add(part);
		}

		MessageInfo info = getBytes(address.split(" "), data.toArray(new MMSPart[data.size()]), subject);
		sendData(info.bytes);
		return info.location;
	}

	private MessageInfo getBytes(String[] recipients, MMSPart[] parts, String subject) throws MmsException {
		final SendReq sendRequest = new SendReq();

		// create send request addresses
		for (int i = 0; i < recipients.length; i++) {
			final EncodedStringValue[] phoneNumbers = EncodedStringValue.extract(recipients[i]);

			if (phoneNumbers != null && phoneNumbers.length > 0)
				sendRequest.addTo(phoneNumbers[0]);
		}

		if (subject != null)
			sendRequest.setSubject(new EncodedStringValue(subject));

		sendRequest.setDate(Calendar.getInstance().getTimeInMillis() / 1000L);
		sendRequest.setFrom(new EncodedStringValue(Utils.getMyPhoneNumber(mContext)));

		final PduBody pduBody = new PduBody();

		// assign parts to the pdu body which contains sending data
		if (parts != null) {
			for (int i = 0; i < parts.length; i++) {
				MMSPart part = parts[i];
				PduPart partPdu = new PduPart();
				partPdu.setName(part.Name.getBytes());
				partPdu.setContentType(part.MimeType.getBytes());

				if (part.MimeType.startsWith("text"))
					partPdu.setCharset(CharacterSets.UTF_8);

				partPdu.setData(part.Data);

				pduBody.addPart(partPdu);
			}
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		SmilXmlSerializer.serialize(SmilHelper.createSmilDocument(pduBody), out);
		PduPart smilPart = new PduPart();
		smilPart.setContentId("smil".getBytes());
		smilPart.setContentLocation("smil.xml".getBytes());
		smilPart.setContentType(ContentType.APP_SMIL.getBytes());
		smilPart.setData(out.toByteArray());
		pduBody.addPart(0, smilPart);

		sendRequest.setBody(pduBody);

		// create byte array which will actually be sent
		final PduComposer composer = new PduComposer(mContext, sendRequest);
		final byte[] bytesToSend = composer.make();

		MessageInfo info = new MessageInfo();
		info.bytes = bytesToSend;

		PduPersister persister = PduPersister.getPduPersister(mContext);
		info.location = persister.persist(sendRequest, Telephony.Mms.Outbox.CONTENT_URI, true, mSettings.getGroup(), null);

		return info;
	}

	private class MessageInfo {
		public Uri location;
		public byte[] bytes;
	}

	private void sendData(final byte[] bytesToSend) throws IOException, SocketException {
		ConnectivityManager connectivityManager = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo info = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE_MMS);
		TransactionSettings settings = new TransactionSettings(mContext, info.getExtraInfo());
		Utils.ensureRouteToHost(settings.getMmscUrl(), settings, connectivityManager);
		HttpUtils.httpConnection(mContext, 4444L, settings.getMmscUrl(), bytesToSend, HttpUtils.HTTP_GET_METHOD, settings.isProxySet(), settings.getProxyAddress(), settings.getProxyPort());
	}

	public boolean checkMMS(Message message) {
		return message.getImages().length != 0 || (message.getMedia().length != 0 && message.getMediaMimeType() != null) || (mSettings.getSendLongAsMms() && Utils.getNumPages(mSettings, message.getText()) > mSettings.getSendLongAsMmsAfter()) || (message.getAddresses().length > 1 && mSettings.getGroup()) || message.getSubject() != null;
	}
}