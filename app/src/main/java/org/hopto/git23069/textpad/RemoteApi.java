package org.hopto.git23069.textpad;

import java.io.IOException;
import java.util.List;
import org.hopto.git23069.textpad.core.Folder;

interface RemoteApi {
    record Remote(String id, String name, String revision, String hash, long size, byte[] content, String source, boolean replacedNewerRevision, String folderId) {
        Remote(String id, String name, String revision, String hash, long size, byte[] content, String source, boolean replacedNewerRevision) {
            this(id, name, revision, hash, size, content, source, replacedNewerRevision, null);
        }
        Remote(String id, String name, String revision, String hash, long size, byte[] content) {
            this(id, name, revision, hash, size, content, "", false, null);
        }
    }
    List<Remote> list() throws IOException;
    Remote get(String id) throws IOException;
    Remote put(String id, String name, byte[] bytes, String baseRevision, String requestId) throws IOException;
    List<Remote> revisions(String id) throws IOException;
    Remote revision(String id, String revision) throws IOException;
    default List<Remote> prune(String id) throws IOException { return revisions(id); }
    record Errands(List<String> hashtags, String updatedAt) {}
    default Errands getErrands() throws IOException { return new Errands(List.of(), ""); }
    default Errands putErrands(List<String> hashtags) throws IOException { return new Errands(hashtags, ""); }
    default List<TagGroup> getTagGroups() throws IOException { throw new IOException("Tag groups are unavailable on this server."); }
    default TagGroup putTagGroup(TagGroup group) throws IOException { throw new IOException("Tag groups are unavailable on this server."); }
    default void deleteTagGroup(String id) throws IOException { throw new IOException("Tag groups are unavailable on this server."); }
    default List<Folder> folders() throws IOException { throw new IOException("Folders are unavailable on this server."); }
    default Folder folder(String id) throws IOException { throw new IOException("Folders are unavailable on this server."); }
    default Folder putFolder(String id, String name, String parentId) throws IOException { throw new IOException("Folders are unavailable on this server."); }
    default void deleteFolder(String id) throws IOException { throw new IOException("Folders are unavailable on this server."); }
    default Remote moveDocument(String id, String folderId) throws IOException { throw new IOException("Folders are unavailable on this server."); }
    default List<Remote> moveDocuments(List<String> ids, String folderId) throws IOException {
        if (ids.size() == 1) return List.of(moveDocument(ids.get(0), folderId));
        throw new IOException("Moving several files requires an updated server.");
    }
    default Remote renameDocument(String id, String name, String baseRevision, String requestId) throws IOException {
        throw new IOException("Renaming files requires an updated server.");
    }
    default Folder renameFolder(String id, String name) throws IOException { return putFolder(id, name, folder(id).parentId()); }
    default Remote createDocument(String id, String name, byte[] bytes, String requestId, String folderId) throws IOException {
        if (folderId != null) throw new IOException("Creating files inside folders requires an updated server.");
        return put(id, name, bytes, null, requestId);
    }
}

