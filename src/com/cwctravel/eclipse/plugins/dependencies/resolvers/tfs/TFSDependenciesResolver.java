package com.cwctravel.eclipse.plugins.dependencies.resolvers.tfs;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;

import com.cwctravel.eclipse.plugins.dependencies.resolvers.IDependenciesResolver;
import com.microsoft.tfs.client.common.server.TFSServer;
import com.microsoft.tfs.client.common.util.ProgressMonitorTaskMonitorAdapter;
import com.microsoft.tfs.client.eclipse.TFSEclipseClientPlugin;
import com.microsoft.tfs.core.clients.versioncontrol.GetOptions;
import com.microsoft.tfs.core.clients.versioncontrol.VersionControlClient;
import com.microsoft.tfs.core.clients.versioncontrol.exceptions.VersionControlException;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.DeletedState;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.GetRequest;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.Item;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.ItemSet;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.ItemType;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.RecursionType;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.Workspace;
import com.microsoft.tfs.core.clients.versioncontrol.specs.ItemSpec;
import com.microsoft.tfs.core.clients.versioncontrol.specs.version.LatestVersionSpec;
import com.microsoft.tfs.util.HashUtils;
import com.microsoft.tfs.util.StringUtil;
import com.microsoft.tfs.util.tasks.TaskMonitorService;

public class TFSDependenciesResolver implements IDependenciesResolver {
	private final Map<String, Long> resolvedDependenciesTimestampMap = Collections.synchronizedMap(new HashMap<String, Long>());

	private void clearStaleEntries() {
		long currentTime = System.currentTimeMillis();
		Iterator<Map.Entry<String, Long>> resolvedDependenciesTimestampMapEntrySetItr = resolvedDependenciesTimestampMap.entrySet().iterator();
		while(resolvedDependenciesTimestampMapEntrySetItr.hasNext()) {
			Map.Entry<String, Long> resolvedDependenciesTimestampMapEntry = resolvedDependenciesTimestampMapEntrySetItr.next();
			if(currentTime - resolvedDependenciesTimestampMapEntry.getValue() > 5000) {
				resolvedDependenciesTimestampMapEntrySetItr.remove();
			}
		}
	}

	@Override
	public void preResolve() {
		clearStaleEntries();
	}

	@Override
	public int resolve(List<IPath> dependencyPaths, IProgressMonitor progressMonitor) throws IOException {
		int status = IDependenciesResolver.RESOLVE_STATUS_NO_CHANGE;
		if(dependencyPaths != null) {
			Map<TFSServer, Workspace> serverWorkspaceMap = new HashMap<TFSServer, Workspace>();
			Map<TFSServer, List<GetRequest>> serverGetRequestMap = new HashMap<TFSServer, List<GetRequest>>();
			Map<TFSServer, List<ItemSpec>> serverItemSpecMap = new HashMap<TFSServer, List<ItemSpec>>();

			TFSServer tfsServer = TFSEclipseClientPlugin.getDefault().getServerManager().getDefaultServer();

			for(IPath dependencyPath: dependencyPaths) {
				String dependencyLocation = dependencyPath.toOSString();
				if(!resolvedDependenciesTimestampMap.containsKey(dependencyLocation)) {
					File dependencyFile = new File(dependencyLocation);

					if(tfsServer != null) {
							VersionControlClient vCC = tfsServer.getConnection().getVersionControlClient();

							Workspace workspace = serverWorkspaceMap.get(tfsServer);
							if(workspace == null) {
								workspace = vCC.getWorkspace(dependencyLocation);
								if(workspace != null) {
									serverWorkspaceMap.put(tfsServer, workspace);
								}
							}

							if(workspace != null) {
								List<GetRequest> getRequests = serverGetRequestMap.get(tfsServer);
								if(getRequests == null) {
									getRequests = new ArrayList<GetRequest>();
									serverGetRequestMap.put(tfsServer, getRequests);
								}
								if(!dependencyFile.exists() || (dependencyFile.isFile() && dependencyFile.canWrite())) {
									getRequests.add(new GetRequest(new ItemSpec(dependencyLocation, RecursionType.FULL), LatestVersionSpec.INSTANCE));
								}
								else {
									List<ItemSpec> itemSpecs = serverItemSpecMap.get(tfsServer);
									if(itemSpecs == null) {
										itemSpecs = new ArrayList<ItemSpec>();
										serverItemSpecMap.put(tfsServer, itemSpecs);
									}
									itemSpecs.add(new ItemSpec(dependencyLocation, RecursionType.FULL));
								}
							}
					}

					resolvedDependenciesTimestampMap.put(dependencyLocation, System.currentTimeMillis());
				}
			}

			if(progressMonitor != null) {
				TaskMonitorService.pushTaskMonitor(new ProgressMonitorTaskMonitorAdapter(progressMonitor));
			}

			if( tfsServer != null) {
				VersionControlClient vCC = tfsServer.getConnection().getVersionControlClient();

				List<GetRequest> getRequests = serverGetRequestMap.get(tfsServer);
				if(getRequests == null) {
					getRequests = new ArrayList<GetRequest>();
					serverGetRequestMap.put(tfsServer, getRequests);
				}

				List<ItemSpec> itemSpecs = serverItemSpecMap.get(tfsServer);
				if(itemSpecs != null) {
					ItemSpec[] itemSpecsArray = itemSpecs.toArray(new ItemSpec[0]);
					ItemSet[] itemGroupCollection = vCC.getItems(itemSpecsArray, LatestVersionSpec.INSTANCE, DeletedState.NON_DELETED, ItemType.FILE);
					for(int i = 0; i < itemGroupCollection.length; i++) {
						ItemSet itemGroup = itemGroupCollection[i];
						ItemSpec itemSpec = itemSpecsArray[i];

						Item[] items = itemGroup.getItems();
						if(items != null) {
							String serverPath = itemGroup.getQueryPath();
							String pattern = itemGroup.getPattern();
							for(int j=0; j< items.length; j++) {
								Item item = items[j];
								String localPath = null;
								if(pattern != null && !pattern.isEmpty()) {
									localPath = itemSpec.getItem();
								}else {
									localPath = item.getServerItem().replace(serverPath, itemSpec.getItem());
								}
								
								byte[] serverHash = item.getContentHashValue();
								File localFile = new File(localPath);
								if(!localFile.exists() || localFile.canWrite()) {
									getRequests.add(new GetRequest(itemSpec, LatestVersionSpec.INSTANCE));
									break;
								}else {
									byte[] localHash = HashUtils.hashFile(localFile, "MD5");
									if(!Arrays.equals(localHash, serverHash)) {
										getRequests.add(new GetRequest(itemSpec, LatestVersionSpec.INSTANCE));
										break;
									}
								}
							}
						}
					}
				}
			}

			try {
				if( tfsServer != null) {
					List<GetRequest> getRequests = serverGetRequestMap.get(tfsServer);
					if(getRequests != null && !getRequests.isEmpty()) {
						Workspace workspace = serverWorkspaceMap.get(tfsServer);

						workspace.get(getRequests.toArray(new GetRequest[0]), GetOptions.combine(new GetOptions[] {constructGetOption(2), GetOptions.OVERWRITE}));
						status = IDependenciesResolver.RESOLVE_STATUS_RESOLVED;
					}
				}
			}
			catch(VersionControlException vCE) {
				throw new IOException(vCE);
			}
		}

		return status;
	}

	@Override
	public String getName() {
		return "TFS Dependency Resolver";
	}

	@Override
	public void postResolve() {

	}
	
	private static GetOptions constructGetOption(int flag) {
		GetOptions result = null;
		try {
			Constructor<GetOptions> getOptionsConstructor = GetOptions.class.getDeclaredConstructor(int.class);
			getOptionsConstructor.setAccessible(true);
			result = getOptionsConstructor.newInstance(flag);
		} catch (NoSuchMethodException e) {
		} catch (SecurityException e) {
		} catch (InstantiationException e) {
		} catch (IllegalAccessException e) {
		} catch (IllegalArgumentException e) {
		} catch (InvocationTargetException e) {
		}
		return result;
	}

}
