package com.coremedia.labs.plugins.adapters.onedrive;

public interface OneDriveContentHubSettings {

  String getClientId();

  String getClientSecret();

  String getTenantId();

  String getDriveId();

  String getDisplayName();

  /**
   * Optional. Can be used for non english Sharepoint installations, where the documents folder gets a localized name.
   * In german it is called "Dokumente".
   * If empty the OneDriveContentHubAdapter uses the default "Documents".
   */
  String getDocumentsFolderName();
}
