/*
 * Copyright (c) 2015 The Ontario Institute for Cancer Research. All rights reserved.                             
 *                                                                                                               
 * This program and the accompanying materials are made available under the terms of the GNU Public License v3.0.
 * You should have received a copy of the GNU General Public License along with                                  
 * this program. If not, see <http://www.gnu.org/licenses/>.                                                     
 *                                                                                                               
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY                           
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES                          
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT                           
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,                                
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED                          
 * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;                               
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER                              
 * IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN                         
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.icgc.dcc.storage.client.command;

import static com.google.common.base.Objects.firstNonNull;
import static com.google.common.collect.Maps.newHashMap;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.icgc.dcc.storage.client.cli.Parameters.checkParameter;
import static org.icgc.dcc.storage.client.mount.MountService.INTERNAL_OPTIONS;
import static org.icgc.dcc.storage.client.util.Formats.formatBytes;
import static org.icgc.dcc.storage.client.util.Formats.formatBytesUnits;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.icgc.dcc.storage.client.cli.ConverterFactory.MountOptionsConverter;
import org.icgc.dcc.storage.client.cli.ConverterFactory.StorageFileLayoutConverter;
import org.icgc.dcc.storage.client.cli.DirectoryValidator;
import org.icgc.dcc.storage.client.download.DownloadService;
import org.icgc.dcc.storage.client.manifest.ManfiestService;
import org.icgc.dcc.storage.client.manifest.ManifestResource;
import org.icgc.dcc.storage.client.metadata.Entity;
import org.icgc.dcc.storage.client.metadata.MetadataService;
import org.icgc.dcc.storage.client.mount.MountService;
import org.icgc.dcc.storage.client.mount.MountStorageContext;
import org.icgc.dcc.storage.client.transport.StorageService;
import org.icgc.dcc.storage.core.model.ObjectInfo;
import org.icgc.dcc.storage.fs.StorageFileLayout;
import org.icgc.dcc.storage.fs.StorageFileSystems;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;

import lombok.SneakyThrows;
import lombok.val;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@Parameters(separators = "=", commandDescription = "Mount a read-only FUSE file system view of the remote storage repository")
public class MountCommand extends AbstractClientCommand {

  /**
   * Constants.
   */
  private static final String FUSE_README_URL = "http://sourceforge.net/p/fuse/fuse/ci/master/tree/README?format=raw";

  /**
   * Options
   */
  @Parameter(names = "--mount-point", description = "the mount point of the FUSE file system. This must exist, be empty and be executable by the current user.", required = true, validateValueWith = DirectoryValidator.class)
  private File mountPoint;
  @Parameter(names = "--manifest", description = "manifest id (from the Data Portal), url or file path")
  private ManifestResource manifestResource;
  @Parameter(names = "--layout", description = "layout of the mount point. One of 'bundle' (nest files in bundle directory) or 'object-id' (flat list of files named by their associated object id)", converter = StorageFileLayoutConverter.class)
  private StorageFileLayout layout = StorageFileLayout.BUNDLE;
  @Parameter(names = "--cache-metadata", description = "to speedup load times, cache metadata on disk locally and use if available")
  private boolean cacheMetadata;
  @Parameter(names = "--options", description = "the mount options of the file system (e.g. --options user_allow_other,allow_other,fsname=icgc,debug) "
      + "in addition to those specified internally: " + INTERNAL_OPTIONS + ". See " + FUSE_README_URL
      + " for details", converter = MountOptionsConverter.class)
  private Map<String, String> options = newHashMap();

  /**
   * Dependencies.
   */
  @Autowired
  private ManfiestService manfiestService;
  @Autowired
  private MetadataService metadataServices;
  @Autowired
  private StorageService storageService;
  @Autowired
  private DownloadService downloadService;
  @Autowired
  private MountService mountService;

  @Override
  public int execute() throws Exception {
    checkParameter(mountPoint.canExecute(), "Cannot mount to '%s'. Please check directory permissions and try again",
        mountPoint);
    checkParameter(mountPoint.list().length == 0, "Cannot mount to '%s'. Please ensure the directory is empty",
        mountPoint);

    try {
      int i = 1;

      //
      // Collect and index metadata
      //

      terminal.printStatus(i++, "Indexing remote entities. Please wait");
      val entities = terminal.printWaiting(this::resolveEntities);

      terminal.printStatus(i++, "Indexing remote objects. Please wait");
      List<ObjectInfo> objects = terminal.printWaiting(this::resolveObjects);
      if (hasManifest()) {
        // Manifest is a filtered view y'all!
        objects = filterManifestObjects(objects);
      }

      //
      // Check access
      //

      terminal.printStatus(i++, "Checking access. Please wait");
      val context =
          new MountStorageContext(layout, downloadService, entities, objects);
      if (!terminal.printWaiting(context::isAuthorized)) {
        terminal.printError("Access denied");
        return FAILURE_STATUS;
      }

      //
      // Report manifest
      //

      if (hasManifest()) {
        terminal.printStatus(i++, "Applying manifest view:\n");
        reportManifest(context);
      }

      //
      // Mount
      //

      terminal.printStatus(i++, "Mounting file system to '" + mountPoint.getAbsolutePath() + "'");
      terminal.printWaiting(() -> mount(context));
      reportMount();

      //
      // Wait
      //

      // Let the user know we are done when the JVM exits
      val watch = Stopwatch.createStarted();
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        reportSummary(context, watch);
      }));

      // Wait for interrupt
      Thread.sleep(Long.MAX_VALUE);
    } catch (InterruptedException ie) {
      return SUCCESS_STATUS;
    } catch (Exception e) {
      log.error("Unknown error:", e);
      throw e;
    }

    return SUCCESS_STATUS;
  }

  @SneakyThrows
  private void mount(MountStorageContext context) {
    val fileSystem = StorageFileSystems.newFileSystem(context);
    mountService.mount(fileSystem, mountPoint.toPath(), options);
  }

  private void reportManifest(MountStorageContext context) {
    terminal.printLine();
    long totalSize = 0;
    for (val file : context.getFiles()) {
      terminal.println(" - " + file.toString());

      totalSize += file.getSize();
    }
    terminal.printLine();
    terminal.println(" Total size: " + formatBytes(totalSize) + " " + formatBytesUnits(totalSize) + "\n");
  }

  private void reportMount() {
    val location = terminal.value(mountPoint.getAbsolutePath());
    terminal.printStatus(
        terminal.label("Successfully mounted file system at " + location + " and is now ready for use!"));
  }

  private void reportSummary(MountStorageContext context, Stopwatch watch) {
    val time = terminal.value(watch.toString());
    val connects = terminal.value(firstNonNull(context.getMetrics().get("connectCount"), 0) + " connects");
    val n = firstNonNull(context.getMetrics().get("byteCount"), 0L);
    val bytes = terminal.value(formatBytes(n) + " " + formatBytesUnits(n));
    terminal.printStatus(
        terminal.label(
            "Shut down mount after " + time +
                " with a total of " + connects +
                " and " + bytes + " bytes read. Good bye!\n"));
  }

  private List<ObjectInfo> resolveObjects() throws IOException {
    return resolveList("objects", storageService::listObjects, new TypeReference<List<ObjectInfo>>() {});
  }

  private List<Entity> resolveEntities() throws IOException {
    return resolveList("entities", metadataServices::getEntities, new TypeReference<List<Entity>>() {});
  }

  @SneakyThrows
  private <T> List<T> resolveList(String name, Supplier<List<T>> factory, TypeReference<List<T>> typeReference) {
    val cacheFile = new File("." + name + ".cache");
    if (cacheMetadata && cacheFile.exists()) {
      return new ObjectMapper().readValue(cacheFile, typeReference);
    }

    val values = factory.get();
    if (cacheMetadata) {
      new ObjectMapper().writeValue(cacheFile, values);
    }

    return values;
  }

  private boolean hasManifest() {
    return manifestResource != null;
  }

  private List<ObjectInfo> filterManifestObjects(List<ObjectInfo> objects) {
    val manifest = manfiestService.getManifest(manifestResource);

    val objectIds = manifest.getEntries().stream()
        .flatMap(entry -> Stream.of(entry.getFileUuid(), entry.getIndexFileUuid()))
        .collect(toSet());

    return objects.stream()
        .filter(object -> objectIds.contains(object.getId()))
        .collect(toList());
  }

}
