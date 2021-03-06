/*
 *
 *  *  Copyright 2014 Orient Technologies LTD (info(at)orientechnologies.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://www.orientechnologies.com
 *
 */
package com.orientechnologies.orient.graph.sql;

import java.util.List;
import java.util.Map;

import com.orientechnologies.orient.core.command.OCommandDistributedReplicateRequest;
import com.orientechnologies.orient.core.command.OCommandRequest;
import com.orientechnologies.orient.core.command.OCommandRequestText;
import com.orientechnologies.orient.core.command.OCommandResultListener;
import com.orientechnologies.orient.core.db.document.ODatabaseDocument;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.exception.OCommandExecutionException;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.metadata.OMetadataInternal;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.security.ORole;
import com.orientechnologies.orient.core.record.ORecord;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.serialization.serializer.OStringSerializerHelper;
import com.orientechnologies.orient.core.sql.OCommandExecutorSQLAbstract;
import com.orientechnologies.orient.core.sql.OCommandSQLParsingException;
import com.orientechnologies.orient.core.sql.query.OSQLAsynchQuery;
import com.tinkerpop.blueprints.impls.orient.OrientBaseGraph;
import com.tinkerpop.blueprints.impls.orient.OrientGraph;
import com.tinkerpop.blueprints.impls.orient.OrientVertex;
import com.tinkerpop.blueprints.impls.orient.OrientVertexType;

/**
 * SQL DELETE VERTEX command.
 * 
 * @author Luca Garulli
 */
public class OCommandExecutorSQLDeleteVertex extends OCommandExecutorSQLAbstract implements OCommandDistributedReplicateRequest,
    OCommandResultListener {
  public static final String NAME      = "DELETE VERTEX";
  private ORecordId          rid;
  private int                removed   = 0;
  private ODatabaseDocument  database;
  private OCommandRequest    query;
  private String             returning = "COUNT";
  private List<ORecord>      allDeletedRecords;
  private OrientGraph        graph;

  @SuppressWarnings("unchecked")
  public OCommandExecutorSQLDeleteVertex parse(final OCommandRequest iRequest) {
    database = getDatabase();

    init((OCommandRequestText) iRequest);

    parserRequiredKeyword("DELETE");
    parserRequiredKeyword("VERTEX");

    OClass clazz = null;
    String where = null;

    String word = parseOptionalWord(true);
    while (word != null) {

      if (word.startsWith("#")) {
        rid = new ORecordId(word);

      } else if (word.equalsIgnoreCase("from")) {
        final StringBuilder q = new StringBuilder();
        final int newPos = OStringSerializerHelper.getEmbedded(parserText, parserGetCurrentPosition(), -1, q);

        query = database.command(new OSQLAsynchQuery<ODocument>(q.toString(), this));

        parserSetCurrentPosition(newPos);

      } else if (word.equals(KEYWORD_WHERE)) {
        if (clazz == null)
          // ASSIGN DEFAULT CLASS
          clazz = ((OMetadataInternal) database.getMetadata()).getImmutableSchemaSnapshot().getClass(OrientVertexType.CLASS_NAME);

        where = parserGetCurrentPosition() > -1 ? " " + parserText.substring(parserGetPreviousPosition()) : "";
        query = database.command(new OSQLAsynchQuery<ODocument>("select from " + clazz.getName() + where, this));
        break;

      } else if (word.length() > 0) {
        // GET/CHECK CLASS NAME
        clazz = ((OMetadataInternal) database.getMetadata()).getImmutableSchemaSnapshot().getClass(word);
        if (clazz == null)
          throw new OCommandSQLParsingException("Class '" + word + " was not found");
      }

      word = parseOptionalWord(true);
      if (parserIsEnded())
        break;
    }

    if (where == null)
      where = "";
    else
      where = " WHERE " + where;

    if (query == null && rid == null)
      if (clazz == null)
        // DELETE ALL VERTEXES
        query = database.command(new OSQLAsynchQuery<ODocument>("select from V" + where, this));
      else
        query = database.command(new OSQLAsynchQuery<ODocument>("select from " + clazz.getName() + where, this));

    return this;
  }

  /**
   * Execute the command and return the ODocument object created.
   */
  public Object execute(final Map<Object, Object> iArgs) {
    if (rid == null && query == null)
      throw new OCommandExecutionException("Cannot execute the command because it has not been parsed yet");

    if (rid != null) {
      // REMOVE PUNCTUAL RID
      OGraphCommandExecutorSQLFactory.runInTx(new OGraphCommandExecutorSQLFactory.GraphCallBack<Object>() {
        @Override
        public Object call(OrientBaseGraph graph) {
          final OrientVertex v = graph.getVertex(rid);
          if (v != null) {
            v.remove();
            removed = 1;
          }

          return null;
        }
      });
    } else if (query != null) {
      // TARGET IS A CLASS + OPTIONAL CONDITION
      graph = OGraphCommandExecutorSQLFactory.getGraph(false);
      OGraphCommandExecutorSQLFactory.runInTx(graph, new OGraphCommandExecutorSQLFactory.GraphCallBack<Object>() {
        @Override
        public Object call(OrientBaseGraph graph) {
          // TARGET IS A CLASS + OPTIONAL CONDITION
          return query.execute(iArgs);
        }
      });
    } else
      throw new OCommandExecutionException("Invalid target");

    return removed;
  }

  /**
   * Delete the current vertex.
   */
  public boolean result(final Object iRecord) {
    final OIdentifiable id = (OIdentifiable) iRecord;
    if (id.getIdentity().isValid()) {
      final OrientVertex v = graph.getVertex(id);
      if (v != null) {
        v.remove();
        removed++;
      }
    }

    return true;
  }

  @Override
  public String getSyntax() {
    return "DELETE VERTEX <rid>|<class>|FROM <query> [WHERE <conditions>] [LIMIT <max-records>]>";
  }

  @Override
  public void end() {
  }

  @Override
  public int getSecurityOperationType() {
    return ORole.PERMISSION_DELETE;
  }

}
