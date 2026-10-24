package fr.jarvis.nexus.graph

import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.rag.*
import fr.jarvis.nexus.store.*
import java.util.UUID

enum class NodeKind { DOCUMENT, CONCEPT, PERSON, ENTITY, CATEGORY, EVENT, SOURCE }
enum class RelationOrigin { EXPLICIT, SUGGESTED }
data class GraphNode(val id: String, val kind: NodeKind, val label: String, val category: String, val documentId: String?, val sourceUri: String? = null)
data class GraphEdge(val id: String, val from: String, val to: String, val kind: String, val origin: RelationOrigin, val version: Int, val citation: Citation, val quote: String)
data class GraphPage(val collectionId: String, val nodes: List<GraphNode>, val edges: List<GraphEdge>, val nextNode: String?, val nextEdge: String?) {
    fun toJson(): String = "{\"format\":\"jarvis.nexus.graph/1\",\"collectionId\":${json(collectionId)},\"nextNode\":${nextNode?.let(::json) ?: "null"},\"nextEdge\":${nextEdge?.let(::json) ?: "null"},\"nodes\":" + nodes.joinToString(",","[","]") {
        "{\"id\":${json(it.id)},\"kind\":${json(it.kind.name)},\"label\":${json(it.label)},\"category\":${json(it.category)},\"documentId\":${it.documentId?.let(::json) ?: "null"},\"sourceUri\":${it.sourceUri?.let(::json) ?: "null"}}"
    } + ",\"edges\":" + edges.joinToString(",","[","]}") { edge ->
        val citation = ContextBundle(listOf(ContextItem(edge.quote,listOf(edge.citation),0.0)),edge.quote.length,0).asUntrustedJson()
        "{\"id\":${json(edge.id)},\"from\":${json(edge.from)},\"to\":${json(edge.to)},\"kind\":${json(edge.kind)},\"origin\":${json(edge.origin.name)},\"version\":${edge.version},\"evidence\":$citation}"
    }
}

/** Collection-scoped graph in the existing database. Suggested edges never become
 * explicit automatically. Every edge cites a current resolvable fragment.
 * Source-restricted host scopes are refused: graph labels need full-collection access.
 */
class GraphService(private val db: NexusDatabase, private val catalog: KnowledgeCatalogService,
    private val hostScope: () -> SearchScope = { SearchScope(emptySet()) }) {
    private class Refusal(val error: KnowledgeError): RuntimeException()
    private fun access(collectionId: String, operation: Operation) {
        val scope = runCatching { hostScope() }.getOrElse { throw Refusal(KnowledgeError.DENIED) }
        if (!catalog.permits(operation) || scope.sourceIds != null || collectionId !in scope.collectionIds) throw Refusal(KnowledgeError.DENIED)
        require(collectionId.length in 1..128)
    }
    private fun <T> checked(collectionId: String, operation: Operation, action: () -> T): CatalogResult<T> = try {
        access(collectionId,operation); CatalogResult.Ok(action().also { access(collectionId,operation) })
    } catch (e: Refusal) { CatalogResult.Rejected(e.error) }
      catch (e: RagRefusal) { CatalogResult.Rejected(e.error) }
      catch (_: IllegalArgumentException) { CatalogResult.Rejected(KnowledgeError.INVALID_INPUT) }
      catch (_: java.sql.SQLException) { CatalogResult.Rejected(KnowledgeError.STORAGE_FAILURE) }
    private fun text(value: String, max: Int, empty: Boolean = false) { require((empty || value.isNotBlank()) && value.length <= max && value.none { it < ' ' }) }

    fun putNode(collectionId: String, kind: NodeKind, label: String, category: String = "", documentId: String? = null): CatalogResult<GraphNode> = checked(collectionId,Operation.WRITE) {
        text(label,256); text(category,128,true); require(documentId == null || documentId.length in 1..128)
        require(kind != NodeKind.DOCUMENT || documentId != null)
        db.transaction { c ->
            if (documentId != null && c.query("SELECT id FROM document WHERE id=? AND collection_id=? AND deleted=0",documentId,collectionId) { it.getString(1) }.isEmpty()) throw Refusal(KnowledgeError.NOT_FOUND)
            val id = sha256(listOf(collectionId,kind.name,label,documentId ?: "").joinToString("\u0000"))
            val exists = c.query("SELECT id FROM graph_node WHERE id=?",id) { it.getString(1) }.isNotEmpty()
            if (!exists && c.query("SELECT count(*) FROM graph_node WHERE collection_id=?",collectionId) { it.getInt(1) }.single() >= 100000) throw Refusal(KnowledgeError.LIMIT_EXCEEDED)
            c.execute("INSERT INTO graph_node VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET category=excluded.category",id,collectionId,kind.name,label,category,documentId)
            access(collectionId,Operation.WRITE)
            c.execute("INSERT INTO mutation_log(document_id,kind) VALUES(?,?)",documentId,"GRAPH_NODE:$id")
            GraphNode(id,kind,label,category,documentId)
        }
    }

    fun relate(collectionId: String, from: String, to: String, kind: String, origin: RelationOrigin, evidence: Citation): CatalogResult<String> = checked(collectionId,Operation.WRITE) {
        text(kind,64); require(from != to && from.length <= 128 && to.length <= 128)
        db.transaction { c ->
            access(collectionId,Operation.READ)
            CitationResolver(db).resolve(evidence,SearchScope(setOf(collectionId)))
            val count = c.query("SELECT count(*) FROM graph_edge WHERE collection_id=?",collectionId) { it.getInt(1) }.single()
            if (count >= 200000) throw Refusal(KnowledgeError.LIMIT_EXCEEDED)
            val id = UUID.randomUUID().toString()
            c.execute("INSERT INTO graph_edge VALUES(?,?,?,?,?,?,'ACTIVE',1,?,?,?,?,?,?)",id,collectionId,from,to,kind,origin.name,evidence.documentId,evidence.version,evidence.ordinal,evidence.start,evidence.end,evidence.textSha256)
            access(collectionId,Operation.WRITE)
            c.execute("INSERT INTO mutation_log(document_id,kind) VALUES(?,?)",evidence.documentId,"GRAPH_RELATION_${origin.name}:$id")
            id
        }
    }

    fun editRelation(collectionId: String, id: String, expectedVersion: Int, kind: String, origin: RelationOrigin, rejected: Boolean): CatalogResult<Int> = checked(collectionId,Operation.WRITE) {
        text(kind,64); require(expectedVersion in 1 until Int.MAX_VALUE && id.length in 1..128)
        db.transaction { c ->
            val changed = c.execute("UPDATE graph_edge SET kind=?,origin=?,state=?,version=version+1 WHERE id=? AND collection_id=? AND version=?",kind,origin.name,if (rejected) "REJECTED" else "ACTIVE",id,collectionId,expectedVersion)
            if (changed != 1) throw Refusal(KnowledgeError.CONFLICT)
            access(collectionId,Operation.WRITE)
            c.execute("INSERT INTO mutation_log(document_id,kind) VALUES(NULL,?)","GRAPH_EDIT:$id:${expectedVersion+1}")
            expectedVersion+1
        }
    }

    fun page(collectionId: String, afterNode: String = "", afterEdge: String = "", nodeLimit: Int = 200, edgeLimit: Int = 20): CatalogResult<GraphPage> = checked(collectionId,Operation.READ) {
        require(nodeLimit in 1..250 && edgeLimit in 1..1000 && afterNode.length <= 128 && afterEdge.length <= 128)
        db.read { c ->
            val nodes = c.query("SELECT n.*,s.uri AS source_uri FROM graph_node n LEFT JOIN document d ON d.id=n.document_id LEFT JOIN revision r ON r.document_id=d.id AND r.version=d.version LEFT JOIN source s ON s.id=r.source_id WHERE n.collection_id=? AND n.id>? AND (n.document_id IS NULL OR d.deleted=0) ORDER BY n.id LIMIT ?",collectionId,afterNode,nodeLimit) { r ->
                GraphNode(r.getString("id"),NodeKind.valueOf(r.getString("kind")),r.getString("label"),r.getString("category"),r.getString("document_id"),r.getString("source_uri"))
            }
            val edges = c.query("SELECT e.id AS edge_id,e.from_id,e.to_id,e.kind,e.origin,e.version AS edge_version,e.text_sha256 AS edge_text_sha256,f.*,s.id AS source_id,s.uri,s.sha256 FROM graph_edge e JOIN document d ON d.id=e.document_id JOIN fragment f ON f.document_id=e.document_id AND f.version=e.document_version AND f.ordinal=e.ordinal JOIN revision r ON r.document_id=d.id AND r.version=d.version JOIN source s ON s.id=r.source_id JOIN graph_node a ON a.id=e.from_id JOIN graph_node b ON b.id=e.to_id LEFT JOIN document ad ON ad.id=a.document_id LEFT JOIN document bd ON bd.id=b.document_id WHERE e.collection_id=? AND e.id>? AND e.state='ACTIVE' AND d.deleted=0 AND d.version=e.document_version AND (a.document_id IS NULL OR ad.deleted=0) AND (b.document_id IS NULL OR bd.deleted=0) AND f.start_offset=e.start_offset AND f.end_offset=e.end_offset ORDER BY e.id LIMIT ?",collectionId,afterEdge,edgeLimit) { r ->
                if(sha256(r.getString("text"))!=r.getString("edge_text_sha256")) throw Refusal(KnowledgeError.CONFLICT)
                GraphEdge(r.getString("edge_id"),r.getString("from_id"),r.getString("to_id"),r.getString("kind"),RelationOrigin.valueOf(r.getString("origin")),r.getInt("edge_version"),citationRow(r),r.getString("text"))
            }
            GraphPage(collectionId,nodes,edges,if(nodes.size == nodeLimit) nodes.last().id else null,if(edges.size == edgeLimit) edges.last().id else null)
        }
    }
}
