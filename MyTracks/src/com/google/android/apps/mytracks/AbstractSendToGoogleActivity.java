/*
 * Copyright 2012 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.apps.mytracks;

import com.google.android.apps.mytracks.fragments.AddEmailsDialogFragment;
import com.google.android.apps.mytracks.fragments.AddEmailsDialogFragment.AddEmailsCaller;
import com.google.android.apps.mytracks.fragments.CheckPermissionFragment;
import com.google.android.apps.mytracks.fragments.CheckPermissionFragment.CheckPermissionCaller;
import com.google.android.apps.mytracks.fragments.ChooseAccountDialogFragment;
import com.google.android.apps.mytracks.fragments.ChooseAccountDialogFragment.ChooseAccountCaller;
import com.google.android.apps.mytracks.fragments.ChooseActivityDialogFragment;
import com.google.android.apps.mytracks.fragments.ChooseActivityDialogFragment.ChooseActivityCaller;
import com.google.android.apps.mytracks.fragments.ConfirmDialogFragment;
import com.google.android.apps.mytracks.fragments.ConfirmDialogFragment.ConfirmCaller;
import com.google.android.apps.mytracks.io.drive.SendDriveActivity;
import com.google.android.apps.mytracks.io.file.SaveActivity;
import com.google.android.apps.mytracks.io.file.TrackFileFormat;
import com.google.android.apps.mytracks.io.fusiontables.SendFusionTablesActivity;
import com.google.android.apps.mytracks.io.gdata.maps.MapsConstants;
import com.google.android.apps.mytracks.io.maps.ChooseMapActivity;
import com.google.android.apps.mytracks.io.maps.SendMapsActivity;
import com.google.android.apps.mytracks.io.sendtogoogle.SendRequest;
import com.google.android.apps.mytracks.io.sendtogoogle.SendToGoogleUtils;
import com.google.android.apps.mytracks.io.sendtogoogle.UploadResultActivity;
import com.google.android.apps.mytracks.io.spreadsheets.SendSpreadsheetsActivity;
import com.google.android.apps.mytracks.io.sync.SyncUtils;
import com.google.android.apps.mytracks.util.AnalyticsUtils;
import com.google.android.apps.mytracks.util.IntentUtils;
import com.google.android.apps.mytracks.util.PreferencesUtils;
import com.google.android.apps.mytracks.util.StringUtils;
import com.google.android.maps.mytracks.R;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;

/**
 * An abstract class for sending a track to Google services.
 * 
 * @author Jimmy Shih
 */
public abstract class AbstractSendToGoogleActivity extends AbstractMyTracksActivity implements
    ChooseAccountCaller, CheckPermissionCaller, AddEmailsCaller, ChooseActivityCaller,
    ConfirmCaller {

  private static final String TAG = AbstractMyTracksActivity.class.getSimpleName();
  private static final String SEND_REQUEST_KEY = "send_request_key";

  private SendRequest sendRequest;

  public void sendToGoogle(SendRequest request) {
    sendRequest = request;
    new ChooseAccountDialogFragment().show(
        getSupportFragmentManager(), ChooseAccountDialogFragment.CHOOSE_ACCOUNT_DIALOG_TAG);
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    if (savedInstanceState != null) {
      sendRequest = savedInstanceState.getParcelable(SEND_REQUEST_KEY);
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putParcelable(SEND_REQUEST_KEY, sendRequest);
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    switch (requestCode) {
      case SendToGoogleUtils.DRIVE_PERMISSION_REQUEST_CODE:
        SendToGoogleUtils.cancelNotification(this, SendToGoogleUtils.DRIVE_NOTIFICATION_ID);
        if (resultCode == Activity.RESULT_OK) {
          onDrivePermissionSuccess();
        } else {
          onPermissionFailure();
        }
        break;
      case SendToGoogleUtils.FUSION_TABLES_PERMISSION_REQUEST_CODE:
        SendToGoogleUtils.cancelNotification(this, SendToGoogleUtils.FUSION_TABLES_NOTIFICATION_ID);
        if (resultCode == Activity.RESULT_OK) {
          onFusionTablesSuccess();
        } else {
          onPermissionFailure();
        }
        break;
      case SendToGoogleUtils.SPREADSHEET_PERMISSION_REQUEST_CODE:
        SendToGoogleUtils.cancelNotification(this, SendToGoogleUtils.SPREADSHEET_NOTIFICATION_ID);
        if (resultCode == Activity.RESULT_OK) {
          onSpreadsheetsPermissionSuccess();
        } else {
          onPermissionFailure();
        }
        break;
      default:
        super.onActivityResult(requestCode, resultCode, data);
    }
  }

  public void onChooseAccountDone() {
    String googleAccount = PreferencesUtils.getString(
        this, R.string.google_account_key, PreferencesUtils.GOOGLE_ACCOUNT_DEFAULT);
    if (googleAccount == null || googleAccount.equals(PreferencesUtils.GOOGLE_ACCOUNT_DEFAULT)) {
      return;
    }
    sendRequest.setAccount(new Account(googleAccount, Constants.ACCOUNT_TYPE));

    // Check Drive permission
    boolean needDrivePermission = sendRequest.isSendDrive();
    if (!needDrivePermission) {
      boolean defaultTablePublic = PreferencesUtils.getBoolean(
          this, R.string.default_table_public_key, PreferencesUtils.DEFAULT_TABLE_PUBLIC_DEFAULT);
      needDrivePermission = defaultTablePublic && sendRequest.isSendFusionTables();
    }
    if (!needDrivePermission) {
      needDrivePermission = sendRequest.isSendSpreadsheets();
    }

    if (needDrivePermission) {
      Fragment fragment = CheckPermissionFragment.newInstance(
          sendRequest.getAccount().name, SendToGoogleUtils.DRIVE_SCOPE);
      getSupportFragmentManager()
          .beginTransaction().add(fragment, CheckPermissionFragment.CHECK_PERMISSION_TAG).commit();
    } else {
      onDrivePermissionSuccess();
    }
  }

  @Override
  public void onCheckPermissionDone(String scope, boolean success, Intent intent) {
    if (success) {
      if (scope.equals(SendToGoogleUtils.DRIVE_SCOPE)) {
        onDrivePermissionSuccess();
      } else if (scope.equals(SendToGoogleUtils.FUSION_TABLES_SCOPE)) {
        onFusionTablesSuccess();
      } else {
        onSpreadsheetsPermissionSuccess();
      }
    } else {
      if (intent != null) {
        int requestCode;
        if (scope.equals(SendToGoogleUtils.DRIVE_SCOPE)) {
          requestCode = SendToGoogleUtils.DRIVE_PERMISSION_REQUEST_CODE;
        } else if (scope.equals(SendToGoogleUtils.FUSION_TABLES_SCOPE)) {
          requestCode = SendToGoogleUtils.FUSION_TABLES_PERMISSION_REQUEST_CODE;
        } else {
          requestCode = SendToGoogleUtils.SPREADSHEET_PERMISSION_REQUEST_CODE;
        }
        startActivityForResult(intent, requestCode);
      } else {
        onPermissionFailure();
      }
    }
  }

  @Override
  public void onAddEmailsDone(String emails) {
    if (emails != null && !emails.equals("")) {
      sendRequest.setDriveShareEmails(emails);
      Intent intent = IntentUtils.newIntent(this, SendDriveActivity.class)
          .putExtra(SendRequest.SEND_REQUEST_KEY, sendRequest);
      startActivity(intent);
    }
  }

  @Override
  public void onChooseActivityDone(String packageName, String className) {
    if (packageName != null && className != null) {
      sendRequest.setMapsSharePackageName(packageName);
      sendRequest.setMapsShareClassName(className);
      Intent intent = IntentUtils.newIntent(
          this, sendRequest.isMapsExistingMap() ? ChooseMapActivity.class : SendMapsActivity.class)
          .putExtra(SendRequest.SEND_REQUEST_KEY, sendRequest);
      startActivity(intent);
    }
  }

  private void onDrivePermissionSuccess() {
    // Check Maps permission
    if (sendRequest.isSendMaps()) {
      AccountManager.get(this).getAuthToken(
          sendRequest.getAccount(), MapsConstants.SERVICE_NAME, null, this,
          new AccountManagerCallback<Bundle>() {
              @Override
            public void run(AccountManagerFuture<Bundle> future) {
              try {
                if (future.getResult().getString(AccountManager.KEY_AUTHTOKEN) != null) {
                  runOnUiThread(new Runnable() {
                      @Override
                    public void run() {
                      onMapsPermissionSuccess();
                    }
                  });
                  return;
                } else {
                  Log.d(TAG, "auth token is null");
                }
              } catch (OperationCanceledException e) {
                Log.d(TAG, "Unable to get auth token", e);
              } catch (AuthenticatorException e) {
                Log.d(TAG, "Unable to get auth token", e);
              } catch (IOException e) {
                Log.d(TAG, "Unable to get auth token", e);
              }
              runOnUiThread(new Runnable() {
                  @Override
                public void run() {
                  onPermissionFailure();
                }
              });
            }
          }, null);
    } else {
      onMapsPermissionSuccess();
    }
  }

  private void onMapsPermissionSuccess() {
    // Check Fusion Tables permission
    if (sendRequest.isSendFusionTables()) {
      Fragment fragment = CheckPermissionFragment.newInstance(
          sendRequest.getAccount().name, SendToGoogleUtils.FUSION_TABLES_SCOPE);
      getSupportFragmentManager()
          .beginTransaction().add(fragment, CheckPermissionFragment.CHECK_PERMISSION_TAG).commit();
    } else {
      onFusionTablesSuccess();
    }
  }

  private void onFusionTablesSuccess() {
    // Check Spreadsheets permission
    if (sendRequest.isSendSpreadsheets()) {
      Fragment fragment = CheckPermissionFragment.newInstance(
          sendRequest.getAccount().name, SendToGoogleUtils.SPREADSHEET_SCOPE);
      getSupportFragmentManager()
          .beginTransaction().add(fragment, CheckPermissionFragment.CHECK_PERMISSION_TAG).commit();
    } else {
      onSpreadsheetsPermissionSuccess();
    }
  }

  /**
   * On spreadsheets permission success. If
   * <p>
   * isSendDrive and isDriveEnableSync -> enable sync
   * <p>
   * isSendDrive and isDriveShare -> show {@link AddEmailsDialogFragment}
   * <p>
   * isSendDrive -> start {@link SendDriveActivity}
   * <p>
   * isSendMaps and isMapShare -> show {@link ChooseActivityDialogFragment}
   * <p>
   * isSendMaps and isMapsExistingMap -> start {@link ChooseMapActivity}
   * <p>
   * isSendMaps and !isMapsExistingMap -> {@link SendMapsActivity}
   * <p>
   * isSendFusionTables -> start {@link SendFusionTablesActivity}
   * <p>
   * isSendSpreadsheets -> start {@link SendSpreadsheetsActivity}
   * <p>
   * else -> start {@link UploadResultActivity}
   */
  private void onSpreadsheetsPermissionSuccess() {
    Class<?> next;
    if (sendRequest.isSendDrive()) {
      if (sendRequest.isDriveEnableSync()) {
        PreferencesUtils.setBoolean(this, R.string.drive_sync_key, true);

        // Turn off everything
        SyncUtils.disableSync(this);

        // Turn on sync
        ContentResolver.setMasterSyncAutomatically(true);

        // Enable sync for account
        SyncUtils.enableSync(sendRequest.getAccount());
        return;
      } else if (sendRequest.isDriveShare()) {
        AddEmailsDialogFragment.newInstance(sendRequest.getTrackId())
            .show(getSupportFragmentManager(), AddEmailsDialogFragment.ADD_EMAILS_DIALOG_TAG);
        return;
      } else {
        next = SendDriveActivity.class;
      }
    } else if (sendRequest.isSendMaps()) {
      if (sendRequest.isMapsShare()) {
        new ChooseActivityDialogFragment().show(
            getSupportFragmentManager(), ChooseActivityDialogFragment.CHOOSE_ACTIVITY_DIALOG_TAG);
        return;
      }
      next = sendRequest.isMapsExistingMap() ? ChooseMapActivity.class : SendMapsActivity.class;
    } else if (sendRequest.isSendFusionTables()) {
      next = SendFusionTablesActivity.class;
    } else if (sendRequest.isSendSpreadsheets()) {
      next = SendSpreadsheetsActivity.class;
    } else {
      next = UploadResultActivity.class;
    }
    Intent intent = IntentUtils.newIntent(this, next)
        .putExtra(SendRequest.SEND_REQUEST_KEY, sendRequest);
    startActivity(intent);
  }

  /**
   * Call when not able to get permission for a google service.
   */
  private void onPermissionFailure() {
    Toast.makeText(this, R.string.send_google_no_account_permission, Toast.LENGTH_LONG).show();
  }

  protected void confirmShare(long trackId) {
    String shareTrack = PreferencesUtils.getString(
        this, R.string.share_track_key, PreferencesUtils.SHARE_TRACK_DEFAULT);
    String driveValue = getString(R.string.settings_sharing_share_track_drive_value);
    String mapsValue = getString(R.string.settings_sharing_share_track_maps_value);
    if (shareTrack.equals(driveValue)) {
      ConfirmDialogFragment.newInstance(R.string.confirm_share_drive_key,
          PreferencesUtils.CONFIRM_SHARE_DRIVE_DEFAULT,
          getString(R.string.share_track_drive_confirm_message), trackId)
          .show(getSupportFragmentManager(), ConfirmDialogFragment.CONFIRM_DIALOG_TAG);
    } else if (shareTrack.equals(mapsValue)) {
      ConfirmDialogFragment.newInstance(R.string.confirm_share_maps_key,
          PreferencesUtils.CONFIRM_SHARE_MAPS_DEFAULT, StringUtils.getHtml(
              this, R.string.share_track_maps_confirm_message, R.string.maps_public_unlisted_url),
          trackId).show(getSupportFragmentManager(), ConfirmDialogFragment.CONFIRM_DIALOG_TAG);
    } else {
      ConfirmDialogFragment.newInstance(R.string.confirm_share_file_key,
          PreferencesUtils.CONFIRM_SHARE_FILE_DEFAULT,
          getString(R.string.share_track_file_confirm_message), trackId)
          .show(getSupportFragmentManager(), ConfirmDialogFragment.CONFIRM_DIALOG_TAG);
    }
  }

  @Override
  public void onConfirmDone(int confirmId, long trackId) {
    SendRequest newRequest;
    switch (confirmId) {
      case R.string.confirm_share_drive_key:
        AnalyticsUtils.sendPageViews(this, "/action/share_drive");
        newRequest = new SendRequest(trackId);
        newRequest.setSendDrive(true);
        newRequest.setDriveShare(true);
        sendToGoogle(newRequest);
        break;
      case R.string.confirm_share_maps_key:
        AnalyticsUtils.sendPageViews(this, "/action/share_maps");
        newRequest = new SendRequest(trackId);
        newRequest.setSendMaps(true);
        newRequest.setMapsShare(true);
        sendToGoogle(newRequest);
        break;
      case R.string.confirm_share_file_key:
        String shareTrack = PreferencesUtils.getString(
            this, R.string.share_track_key, PreferencesUtils.SHARE_TRACK_DEFAULT);
        TrackFileFormat trackFileFormat;
        if (shareTrack.equals(TrackFileFormat.GPX.name())) {
          trackFileFormat = TrackFileFormat.GPX;
        } else if (shareTrack.equals(TrackFileFormat.KML.name())) {
          trackFileFormat = TrackFileFormat.KML;
        } else if (shareTrack.equals(TrackFileFormat.CSV.name())) {
          trackFileFormat = TrackFileFormat.CSV;
        } else {
          trackFileFormat = TrackFileFormat.TCX;
        }
        Intent intent = IntentUtils.newIntent(this, SaveActivity.class)
            .putExtra(SaveActivity.EXTRA_TRACK_ID, trackId)
            .putExtra(SaveActivity.EXTRA_TRACK_FILE_FORMAT, (Parcelable) trackFileFormat)
            .putExtra(SaveActivity.EXTRA_SHARE_TRACK, true);
        startActivity(intent);
        break;
      default:
    }
  }
}