package org.hopto.git23069.textpad;

import java.io.IOException;
import java.util.List;

interface RemoteApi {
    record Remote(String id, String name, String revision, String hash, long size, byte[] content) {}
    List<Remote> list() throws IOException;
    Remote get(String id) throws IOException;
    Remote put(String id, String name, byte[] bytes, String baseRevision, String requestId) throws IOException;
    List<Remote> revisions(String id) throws IOException;
    Remote revision(String id, String revision) throws IOException;
    default List<Remote> prune(String id) throws IOException { return revisions(id); }
}
