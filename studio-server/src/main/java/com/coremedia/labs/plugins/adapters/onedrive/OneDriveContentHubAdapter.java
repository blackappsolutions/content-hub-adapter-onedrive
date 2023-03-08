package com.coremedia.labs.plugins.adapters.onedrive;

import com.coremedia.contenthub.api.ContentHubAdapter;
import com.coremedia.contenthub.api.ContentHubContext;
import com.coremedia.contenthub.api.ContentHubMimeTypeService;
import com.coremedia.contenthub.api.ContentHubObject;
import com.coremedia.contenthub.api.ContentHubObjectId;
import com.coremedia.contenthub.api.ContentHubTransformer;
import com.coremedia.contenthub.api.ContentHubType;
import com.coremedia.contenthub.api.Folder;
import com.coremedia.contenthub.api.GetChildrenResult;
import com.coremedia.contenthub.api.Item;
import com.coremedia.contenthub.api.exception.ContentHubException;
import com.coremedia.contenthub.api.pagination.PaginationRequest;
import com.coremedia.labs.plugins.adapters.onedrive.model.DriveItemAdapter;
import com.coremedia.labs.plugins.adapters.onedrive.model.OneDriveFolder;
import com.coremedia.labs.plugins.adapters.onedrive.model.OneDriveItem;
import com.coremedia.labs.plugins.adapters.onedrive.service.OneDriveService;
import com.microsoft.graph.models.extensions.Drive;
import com.microsoft.graph.models.extensions.DriveItem;
import com.microsoft.graph.models.extensions.Site;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class OneDriveContentHubAdapter implements ContentHubAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(OneDriveContentHubAdapter.class);

  private final String connectionId;
  private final OneDriveContentHubSettings settings;
  private final ContentHubMimeTypeService mimeTypeService;
  private final Map<ContentHubType, String> itemTypeToContentTypeMapping;

  private final OneDriveService oneDriveService;

  private final OneDriveFolder rootFolder;
  private final Drive drive;

  public OneDriveContentHubAdapter(OneDriveContentHubSettings settings,
                                   String connectionId,
                                   ContentHubMimeTypeService contentHubMimeTypeService,
                                   Map<ContentHubType, String> itemTypeToContentTypeMapping) {
    this.settings = settings;
    this.connectionId = connectionId;
    this.mimeTypeService = contentHubMimeTypeService;
    this.itemTypeToContentTypeMapping = itemTypeToContentTypeMapping;

    oneDriveService = new OneDriveService(
            settings.getClientId(),
            settings.getClientSecret(),
            settings.getTenantId());

    // Init root
    ContentHubObjectId rootHubId = new ContentHubObjectId(connectionId, connectionId);
    DriveItem rootItem;

    String rootDisplayName = settings.getDisplayName();


    String driveId = settings.getDriveId();
    Pattern siteIdPattern = Pattern.compile("(.*):/sites/(.*)");  // Example contoso.sharepoint.com:/sites/my-site
    Matcher siteIdMatcher = siteIdPattern.matcher(driveId);
    if (siteIdMatcher.find()) {
      Site site = oneDriveService.getSite(driveId);
      List<Drive> siteDrives = oneDriveService.getDrivesForSiteName(driveId);
      Optional<Drive> foundDrive = siteDrives.stream().filter(d -> getDocumentsFolderName().equals(d.name)).findFirst();
      if (!foundDrive.isPresent()) {
        // throw Exception
        throw new ContentHubException("Unable to initialize OneDrive connection for given drive id '" + driveId + "'.");
      }
      drive = foundDrive.orElse(null);
      if (StringUtils.isBlank(rootDisplayName)) {
        rootDisplayName = site.displayName;
      }
      rootItem = oneDriveService.getRootItem(drive.id);

    } else {
      drive = oneDriveService.getDrive(driveId);
      rootItem = oneDriveService.getRootItem(drive.id);

      if (StringUtils.isBlank(rootDisplayName)) {
        // Parse name from webUrl
        // Example: https://contoso.sharepoint.com/sites/Example/Shared%20Documents
        // We're using 'Example' instead of 'root'
        Pattern p = Pattern.compile("/sites/(.*)/");
        Matcher m = p.matcher(rootItem.webUrl);
        if (m.find()) {
          rootDisplayName = m.group(1);
        }
      }
    }

    if (StringUtils.isBlank(rootDisplayName)) {
      rootDisplayName = rootItem.name;  // Use item name as fallback
    }

    rootFolder = new OneDriveFolder(rootHubId, rootItem, rootDisplayName);
  }

  /**
   * Fix for this error after login on freshly started studio-server:
   2023-03-08 14:33:21 -  [INFO] (http-nio-41080-exec-10) com.coremedia.springframework.security.impl.Http200AuthenticationSuccessHandler [] - Successful Authentication - User: admin (coremedia:///cap/user/0), IP: 127.0.0.1
   2023-03-08 14:33:22 -  [WARN] (http-nio-41080-exec-6) com.coremedia.cap.common.CapConnection [] - Server: reverse lookup of address 192.168.188.21 resolves to hostname 192.168.188.21, expected name is macBookAir2022.local
   2023-03-08 14:33:23 -  [INFO] (http-nio-41080-exec-3) com.coremedia.labs.plugins.adapters.onedrive.service.OneDriveService [] - Fetching Site 'blackapp.sharepoint.com:/sites/corem-test'.
   2023-03-08 14:33:24 -  [INFO] (http-nio-41080-exec-3) com.coremedia.labs.plugins.adapters.onedrive.service.OneDriveService [] - Fetching Drives for SharePoint site 'blackapp.sharepoint.com:/sites/corem-test'.
   2023-03-08 14:33:24 -  [INFO] (http-nio-41080-exec-3) com.coremedia.labs.plugins.adapters.onedrive.service.OneDriveService [] - Fetching Site 'blackapp.sharepoint.com:/sites/corem-test'.
   2023-03-08 14:33:24 -  [INFO] (http-nio-41080-exec-3) com.coremedia.labs.plugins.adapters.onedrive.service.OneDriveService [] - Fetching Drives for SharePoint site 'blackapp.sharepoint.com,544b38f5-d2d1-4dd1-b906-1e454a24983c,3b6e8101-136b-4176-9115-77387d4bbbc1'.
   2023-03-08 14:33:25 - [ERROR] (http-nio-41080-exec-3) com.coremedia.contenthub.lib.ContentHubManager [] - Failed to initialize adapter for connection 'onedrive-asys':
                                                         Unable to initialize OneDrive connection for given drive id 'blackapp.sharepoint.com:/sites/corem-test'.
   */
  private String getDocumentsFolderName() {
    String documentsFolderName = settings.getDocumentsFolderName(); // see also javadoc of this method
    return StringUtils.isEmpty(documentsFolderName) ? "Documents" : documentsFolderName;
  }

  // --- ContentHubAdapter ---

  @Override
  public Folder getRootFolder(ContentHubContext context) throws ContentHubException {
    return rootFolder;
  }

  @Nullable
  @Override
  public Folder getFolder(ContentHubContext context, ContentHubObjectId id) throws ContentHubException {
    LOG.debug("Get folder with id {}.", id);

    if (rootFolder.getId().equals(id)) {
      return rootFolder;
    }

    DriveItem item = oneDriveService.getItem(drive.id, id.getExternalId());
    return new OneDriveFolder(new ContentHubObjectId(connectionId, item.id), item);
  }

  @Nullable
  @Override
  public Folder getParent(ContentHubContext context, ContentHubObject contentHubObject) throws ContentHubException {
    LOG.debug("Get item with for {}.", contentHubObject.getId());

    OneDriveFolder parent = null;
    if (rootFolder.equals(contentHubObject)) {
      return null;
    }

    if (contentHubObject instanceof DriveItemAdapter) {
      DriveItemAdapter child = (DriveItemAdapter) contentHubObject;
      String parentId = child.getDriveItem().parentReference.id;
      if (StringUtils.isNotBlank(parentId)) {
        DriveItem parentItem = oneDriveService.getItem(drive.id, parentId);
        parent = createFolder(parentItem);
      }
    }

    return parent;
  }

  @Nullable
  @Override
  public Item getItem(ContentHubContext context, ContentHubObjectId id) throws ContentHubException {
    LOG.debug("Get item with id {}.", id);

    DriveItem item = oneDriveService.getItem(drive.id, id.getExternalId());
    ContentHubObjectId hubId = new ContentHubObjectId(connectionId, item.id);
    return new OneDriveItem(hubId, item, oneDriveService, mimeTypeService, itemTypeToContentTypeMapping);
  }

  @Override
  public GetChildrenResult getChildren(ContentHubContext context, Folder folder, @Nullable PaginationRequest paginationRequest) {
    LOG.debug("Get children of {}.", folder);

    if (folder instanceof OneDriveFolder) {
      OneDriveFolder oneDriveFolder = (OneDriveFolder) folder;
      List<DriveItem> driveItems = oneDriveService.getChildren(drive.id, oneDriveFolder.getDriveItem());
      return new GetChildrenResult(createContentHubObjects(driveItems));
    }
    return new GetChildrenResult(Collections.emptyList());
  }

  @Override
  public ContentHubTransformer transformer() {
    return new OneDriveContentHubTransformer(oneDriveService, mimeTypeService);
  }


  // --- private ---

  private List<ContentHubObject> createContentHubObjects(List<DriveItem> driveItems) {
    return driveItems.stream().map(this::createContentHubObject).collect(Collectors.toList());
  }

  private ContentHubObject createContentHubObject(DriveItem driveItem) {
    return driveItem.folder != null ? createFolder(driveItem) : createItem(driveItem);
  }

  private OneDriveFolder createFolder(DriveItem driveItem) {
    ContentHubObjectId hubId = new ContentHubObjectId(connectionId, driveItem.id);
    return new OneDriveFolder(hubId, driveItem);
  }

  private OneDriveItem createItem(DriveItem driveItem) {
    ContentHubObjectId hubId = new ContentHubObjectId(connectionId, driveItem.id);
    return new OneDriveItem(hubId, driveItem, oneDriveService, mimeTypeService, itemTypeToContentTypeMapping);
  }

}
