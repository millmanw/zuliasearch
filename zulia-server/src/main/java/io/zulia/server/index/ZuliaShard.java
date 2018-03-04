package io.zulia.server.index;

import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.protobuf.ByteString;
import io.zulia.ZuliaConstants;
import io.zulia.message.ZuliaBase.Metadata;
import io.zulia.message.ZuliaBase.ResultDocument;
import io.zulia.message.ZuliaBase.ShardCountResponse;
import io.zulia.message.ZuliaBase.Similarity;
import io.zulia.message.ZuliaIndex.AnalyzerSettings;
import io.zulia.message.ZuliaIndex.FieldConfig;
import io.zulia.message.ZuliaQuery;
import io.zulia.message.ZuliaQuery.AnalysisResult;
import io.zulia.message.ZuliaQuery.CountRequest;
import io.zulia.message.ZuliaQuery.FacetCount;
import io.zulia.message.ZuliaQuery.FacetGroup;
import io.zulia.message.ZuliaQuery.FacetRequest;
import io.zulia.message.ZuliaQuery.FetchType;
import io.zulia.message.ZuliaQuery.FieldSort;
import io.zulia.message.ZuliaQuery.HighlightRequest;
import io.zulia.message.ZuliaQuery.HighlightResult;
import io.zulia.message.ZuliaQuery.ScoredResult;
import io.zulia.message.ZuliaQuery.ShardQueryResponse;
import io.zulia.message.ZuliaQuery.SortRequest;
import io.zulia.message.ZuliaQuery.SortValue;
import io.zulia.message.ZuliaQuery.SortValues;
import io.zulia.message.ZuliaServiceOuterClass.GetFieldNamesResponse;
import io.zulia.message.ZuliaServiceOuterClass.GetTermsRequest;
import io.zulia.message.ZuliaServiceOuterClass.GetTermsResponse;
import io.zulia.server.analysis.ZuliaPerFieldAnalyzer;
import io.zulia.server.analysis.highlight.ZuliaHighlighter;
import io.zulia.server.analysis.similarity.ConstantSimilarity;
import io.zulia.server.analysis.similarity.TFSimilarity;
import io.zulia.server.config.ServerIndexConfig;
import io.zulia.server.index.field.FieldTypeUtil;
import io.zulia.server.search.QueryCacheKey;
import io.zulia.server.search.QueryResultCache;
import io.zulia.util.ResultHelper;
import io.zulia.util.ZuliaUtil;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.document.Document;
import org.apache.lucene.facet.FacetResult;
import org.apache.lucene.facet.Facets;
import org.apache.lucene.facet.FacetsCollector;
import org.apache.lucene.facet.LabelAndValue;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.search.highlight.Fragmenter;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.search.highlight.SimpleHTMLFormatter;
import org.apache.lucene.search.highlight.SimpleSpanFragmenter;
import org.apache.lucene.search.highlight.TextFragment;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.search.similarities.PerFieldSimilarityWrapper;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ZuliaShard {

	private final static Logger LOG = Logger.getLogger(ZuliaShard.class.getSimpleName());
	private static Pattern sortedDocValuesMessage = Pattern.compile(
			"unexpected docvalues type NONE for field '(.*)' \\(expected one of \\[SORTED, SORTED_SET\\]\\)\\. Use UninvertingReader or index with docvalues\\.");
	private final int shardNumber;
	private final ServerIndexConfig indexConfig;
	private final AtomicLong counter;
	private final Set<String> fetchSet;
	private final Set<String> fetchSetWithMeta;
	private final Set<String> fetchSetWithDocument;

	private final ShardReaderManager shardReaderManager;
	private final ShardWriteManager shardWriteManager;

	private Long lastCommit;
	private Long lastChange;
	private String indexName;
	private QueryResultCache queryResultCache;

	private int segmentQueryCacheMaxAmount;

	private final boolean primary;

	//
	public ZuliaShard(int shardNumber, ShardWriteManager shardWriteManager, ServerIndexConfig indexConfig, boolean primary) throws Exception {

		this.primary = primary;
		this.shardNumber = shardNumber;
		this.indexConfig = indexConfig;
		this.shardWriteManager = shardWriteManager;

		updateIndexSettings();

		this.shardReaderManager = new ShardReaderManager(shardWriteManager);
		this.shardReaderManager.addListener(new ReferenceManager.RefreshListener() {
			@Override
			public void beforeRefresh() {

			}

			@Override
			public void afterRefresh(boolean didRefresh) {
				if (didRefresh) {
					QueryResultCache qrc = queryResultCache;
					if (qrc != null) {
						qrc.clear();
					}
				}
			}
		});

		this.fetchSet = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(ZuliaConstants.ID_FIELD, ZuliaConstants.TIMESTAMP_FIELD)));

		this.fetchSetWithMeta = Collections
				.unmodifiableSet(new HashSet<>(Arrays.asList(ZuliaConstants.ID_FIELD, ZuliaConstants.TIMESTAMP_FIELD, ZuliaConstants.STORED_META_FIELD)));

		this.fetchSetWithDocument = Collections.unmodifiableSet(new HashSet<>(
				Arrays.asList(ZuliaConstants.ID_FIELD, ZuliaConstants.TIMESTAMP_FIELD, ZuliaConstants.STORED_META_FIELD, ZuliaConstants.STORED_DOC_FIELD)));

		this.counter = new AtomicLong();
		this.lastCommit = null;
		this.lastChange = null;
		this.indexName = indexConfig.getIndexName();

	}

	public boolean isPrimary() {
		return primary;
	}

	private void setupCaches(ServerIndexConfig indexConfig) {
		segmentQueryCacheMaxAmount = indexConfig.getIndexSettings().getShardQueryCacheMaxAmount();

		int segmentQueryCacheSize = indexConfig.getIndexSettings().getShardQueryCacheSize();
		if ((segmentQueryCacheSize > 0)) {
			this.queryResultCache = new QueryResultCache(segmentQueryCacheSize, 8);
		}
		else {
			this.queryResultCache = null;
		}

	}

	public void updateIndexSettings() {
		setupCaches(indexConfig);
		shardWriteManager.updateIndexSetting(indexConfig);
	}

	public int getShardNumber() {
		return shardNumber;
	}

	public ShardQueryResponse queryShard(Query query, Map<String, Similarity> similarityOverrideMap, int amount, FieldDoc after, FacetRequest facetRequest,
			SortRequest sortRequest, QueryCacheKey queryCacheKey, FetchType resultFetchType, List<String> fieldsToReturn, List<String> fieldsToMask,
			List<HighlightRequest> highlightList, List<ZuliaQuery.AnalysisRequest> analysisRequestList, boolean debug) throws Exception {

		shardReaderManager.maybeRefreshBlocking();
		ShardReader shardReader = shardReaderManager.acquire();

		try {

			QueryResultCache qrc = queryResultCache;

			boolean useCache = (qrc != null) && ((segmentQueryCacheMaxAmount <= 0) || (segmentQueryCacheMaxAmount >= amount)) && queryCacheKey != null;
			if (useCache) {
				ShardQueryResponse cacheShardResponse = qrc.getCachedShardQueryResponse(queryCacheKey);
				if (cacheShardResponse != null) {
					return cacheShardResponse;
				}
			}

			PerFieldSimilarityWrapper similarity = getSimilarity(similarityOverrideMap);

			IndexSearcher indexSearcher = shardReader.getSearcher(similarity);

			if (debug) {
				LOG.info("Lucene Query for index <" + indexName + "> segment <" + shardNumber + ">: " + query);
				LOG.info("Rewritten Query for index <" + indexName + "> segment <" + shardNumber + ">: " + indexSearcher.rewrite(query));
			}

			int hasMoreAmount = amount + 1;

			TopDocsCollector<?> collector;

			boolean sorting = (sortRequest != null) && !sortRequest.getFieldSortList().isEmpty();
			if (sorting) {

				collector = getSortingCollector(sortRequest, hasMoreAmount, after);
			}
			else {
				collector = TopScoreDocCollector.create(hasMoreAmount, after);
			}

			ShardQueryResponse.Builder shardQueryReponseBuilder = ShardQueryResponse.newBuilder();

			if ((facetRequest != null) && !facetRequest.getCountRequestList().isEmpty()) {

				searchWithFacets(shardReader, facetRequest, query, indexSearcher, collector, shardQueryReponseBuilder);

			}
			else {
				indexSearcher.search(query, collector);
			}

			ScoreDoc[] results = collector.topDocs().scoreDocs;

			int totalHits = collector.getTotalHits();

			shardQueryReponseBuilder.setTotalHits(totalHits);

			boolean moreAvailable = (results.length == hasMoreAmount);

			int numResults = Math.min(results.length, amount);

			List<ZuliaHighlighter> highlighterList = getHighlighterList(highlightList, query);

			List<AnalysisHandler> analysisHandlerList = getAnalysisHandlerList(shardReader, analysisRequestList);

			for (int i = 0; i < numResults; i++) {
				ScoredResult.Builder srBuilder = handleDocResult(indexSearcher, sortRequest, sorting, results, i, resultFetchType, fieldsToReturn, fieldsToMask,
						highlighterList, analysisHandlerList);

				shardQueryReponseBuilder.addScoredResult(srBuilder.build());
			}

			if (moreAvailable) {
				ScoredResult.Builder srBuilder = handleDocResult(indexSearcher, sortRequest, sorting, results, numResults, FetchType.NONE,
						Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
				shardQueryReponseBuilder.setNext(srBuilder);
			}

			shardQueryReponseBuilder.setIndexName(indexName);
			shardQueryReponseBuilder.setShardNumber(shardNumber);

			if (!analysisHandlerList.isEmpty()) {
				for (AnalysisHandler analysisHandler : analysisHandlerList) {
					AnalysisResult segmentAnalysisResult = analysisHandler.getShardResult();
					if (segmentAnalysisResult != null) {
						shardQueryReponseBuilder.addAnalysisResult(segmentAnalysisResult);
					}
				}
			}

			ShardQueryResponse segmentResponse = shardQueryReponseBuilder.build();
			if (useCache) {
				qrc.storeInCache(queryCacheKey, segmentResponse);
			}
			return segmentResponse;
		}
		catch (IllegalStateException e) {
			Matcher m = sortedDocValuesMessage.matcher(e.getMessage());
			if (m.matches()) {
				String field = m.group(1);
				throw new Exception("Field <" + field + "> must have sortAs defined to be sortable");
			}

			throw e;
		}
		finally {
			shardReaderManager.decRef(shardReader);
		}
	}

	private List<AnalysisHandler> getAnalysisHandlerList(ShardReader shardReader, List<ZuliaQuery.AnalysisRequest> analysisRequests) throws Exception {
		if (analysisRequests.isEmpty()) {
			return Collections.emptyList();
		}

		List<AnalysisHandler> analysisHandlerList = new ArrayList<>();
		for (ZuliaQuery.AnalysisRequest analysisRequest : analysisRequests) {

			Analyzer analyzer = shardWriteManager.getAnalyzer();

			String analyzerOverride = analysisRequest.getAnalyzerOverride();
			if (analyzerOverride != null && !analyzerOverride.isEmpty()) {
				String analyzerName = analyzerOverride;

				AnalyzerSettings analyzerSettings = indexConfig.getAnalyzerSettingsByName(analyzerName);
				if (analyzerSettings != null) {
					analyzer = ZuliaPerFieldAnalyzer.getAnalyzerForField(analyzerSettings);
				}
				else {
					throw new RuntimeException("Invalid analyzer name <" + analyzerName + ">");
				}
			}

			AnalysisHandler analysisHandler = new AnalysisHandler(shardReader, analyzer, indexConfig, analysisRequest);
			analysisHandlerList.add(analysisHandler);
		}
		return analysisHandlerList;

	}

	private List<ZuliaHighlighter> getHighlighterList(List<HighlightRequest> highlightRequests, Query q) {

		if (highlightRequests.isEmpty()) {
			return Collections.emptyList();
		}

		List<ZuliaHighlighter> highlighterList = new ArrayList<>();

		for (HighlightRequest highlight : highlightRequests) {
			QueryScorer queryScorer = new QueryScorer(q, highlight.getField());
			queryScorer.setExpandMultiTermQuery(true);
			Fragmenter fragmenter = new SimpleSpanFragmenter(queryScorer, highlight.getFragmentLength());
			SimpleHTMLFormatter simpleHTMLFormatter = new SimpleHTMLFormatter(highlight.getPreTag(), highlight.getPostTag());
			ZuliaHighlighter highlighter = new ZuliaHighlighter(simpleHTMLFormatter, queryScorer, highlight);
			highlighter.setTextFragmenter(fragmenter);
			highlighterList.add(highlighter);
		}
		return highlighterList;
	}

	private PerFieldSimilarityWrapper getSimilarity(Map<String, Similarity> similarityOverrideMap) {
		return new PerFieldSimilarityWrapper() {
			@Override
			public org.apache.lucene.search.similarities.Similarity get(String name) {

				AnalyzerSettings analyzerSettings = indexConfig.getAnalyzerSettingsForIndexField(name);
				Similarity similarity = Similarity.BM25;
				if (analyzerSettings != null && analyzerSettings.getSimilarity() != null) {
					similarity = analyzerSettings.getSimilarity();
				}

				if (similarityOverrideMap != null) {
					Similarity fieldSimilarityOverride = similarityOverrideMap.get(name);
					if (fieldSimilarityOverride != null) {
						similarity = fieldSimilarityOverride;
					}
				}

				if (Similarity.TFIDF.equals(similarity)) {
					return new ClassicSimilarity();
				}
				else if (Similarity.BM25.equals(similarity)) {
					return new BM25Similarity();
				}
				else if (Similarity.CONSTANT.equals(similarity)) {
					return new ConstantSimilarity();
				}
				else if (Similarity.TF.equals(similarity)) {
					return new TFSimilarity();
				}
				else {
					throw new RuntimeException("Unknown similarity type <" + similarity + ">");
				}
			}
		};
	}

	private void searchWithFacets(ShardReader shardReader, FacetRequest facetRequest, Query q, IndexSearcher indexSearcher, TopDocsCollector<?> collector,
			ShardQueryResponse.Builder segmentReponseBuilder) throws Exception {
		FacetsCollector facetsCollector = new FacetsCollector();
		indexSearcher.search(q, MultiCollector.wrap(collector, facetsCollector));

		Facets facets = shardReader.getFacets(facetsCollector);

		for (CountRequest countRequest : facetRequest.getCountRequestList()) {

			String label = countRequest.getFacetField().getLabel();

			if (!indexConfig.existingFacet(label)) {
				throw new IllegalArgumentException(label + " is not defined as a facetable field");
			}

			FacetResult facetResult = null;

			try {

				int numOfFacets;
				if (indexConfig.getNumberOfShards() > 1) {
					if (countRequest.getShardFacets() > 0) {
						numOfFacets = countRequest.getShardFacets();
					}
					else if (countRequest.getShardFacets() == 0) {
						numOfFacets = countRequest.getMaxFacets() * 10;
					}
					else {
						numOfFacets = shardReader.getTotalFacets();
					}
				}
				else {
					if (countRequest.getMaxFacets() > 0) {
						numOfFacets = countRequest.getMaxFacets();
					}
					else {
						numOfFacets = shardReader.getTotalFacets();
					}
				}

				facetResult = facets.getTopChildren(numOfFacets, label);
			}
			catch (UncheckedExecutionException e) {
				Throwable cause = e.getCause();
				if (cause.getMessage().contains(" was not indexed with SortedSetDocValues")) {
					//this is when no data has been indexing into a facet or facet does not exist
				}
				else {
					throw e;
				}
			}
			catch (IllegalArgumentException e) {
				if (e.getMessage().equals("dimension \"" + label + "\" was not indexed")) {
					//this is when no data has been indexing into a facet or facet does not exist
				}
				else {
					throw e;
				}
			}
			FacetGroup.Builder fg = FacetGroup.newBuilder();
			fg.setCountRequest(countRequest);

			if (facetResult != null) {

				for (LabelAndValue subResult : facetResult.labelValues) {
					FacetCount.Builder facetCountBuilder = FacetCount.newBuilder();
					facetCountBuilder.setCount(subResult.value.longValue());
					facetCountBuilder.setFacet(subResult.label);
					fg.addFacetCount(facetCountBuilder);
				}
			}
			segmentReponseBuilder.addFacetGroup(fg);
		}
	}

	private TopDocsCollector<?> getSortingCollector(SortRequest sortRequest, int hasMoreAmount, FieldDoc after) throws Exception {
		List<SortField> sortFields = new ArrayList<>();
		TopDocsCollector<?> collector;
		for (FieldSort fs : sortRequest.getFieldSortList()) {
			boolean reverse = FieldSort.Direction.DESCENDING.equals(fs.getDirection());

			String sortField = fs.getSortField();
			FieldConfig.FieldType sortFieldType = indexConfig.getFieldTypeForSortField(sortField);

			if (FieldTypeUtil.isNumericOrDateFieldType(sortFieldType)) {

				SortedNumericSelector.Type sortedNumericSelector = SortedNumericSelector.Type.MIN;
				if (reverse) {
					sortedNumericSelector = SortedNumericSelector.Type.MAX;
				}

				SortField.Type type;
				if (FieldTypeUtil.isNumericIntFieldType(sortFieldType)) {
					type = SortField.Type.INT;
				}
				else if (FieldTypeUtil.isNumericLongFieldType(sortFieldType)) {
					type = SortField.Type.LONG;
				}
				else if (FieldTypeUtil.isNumericFloatFieldType(sortFieldType)) {
					type = SortField.Type.FLOAT;
				}
				else if (FieldTypeUtil.isNumericDoubleFieldType(sortFieldType)) {
					type = SortField.Type.DOUBLE;
				}
				else if (FieldTypeUtil.isDateFieldType(sortFieldType)) {
					type = SortField.Type.LONG;
				}
				else {
					throw new Exception("Invalid numeric sort type <" + sortFieldType + "> for sort field <" + sortField + ">");
				}
				sortFields.add(new SortedNumericSortField(sortField, type, reverse, sortedNumericSelector));
			}
			else {

				SortedSetSelector.Type sortedSetSelector = SortedSetSelector.Type.MIN;
				if (reverse) {
					sortedSetSelector = SortedSetSelector.Type.MAX;
				}

				sortFields.add(new SortedSetSortField(sortField, reverse, sortedSetSelector));
			}

		}
		Sort sort = new Sort();
		sort.setSort(sortFields.toArray(new SortField[sortFields.size()]));

		collector = TopFieldCollector.create(sort, hasMoreAmount, after, true, true, true);
		return collector;
	}

	private ScoredResult.Builder handleDocResult(IndexSearcher is, SortRequest sortRequest, boolean sorting, ScoreDoc[] results, int i,
			FetchType resultFetchType, List<String> fieldsToReturn, List<String> fieldsToMask, List<ZuliaHighlighter> highlighterList,
			List<AnalysisHandler> analysisHandlerList) throws Exception {
		int luceneShardId = results[i].doc;

		Set<String> fieldsToFetch = fetchSet;

		if (FetchType.FULL.equals(resultFetchType)) {
			fieldsToFetch = fetchSetWithDocument;
		}
		else if (FetchType.META.equals(resultFetchType)) {
			fieldsToFetch = fetchSetWithMeta;
		}

		Document d = is.doc(luceneShardId, fieldsToFetch);

		IndexableField f = d.getField(ZuliaConstants.TIMESTAMP_FIELD);
		long timestamp = f.numericValue().longValue();

		ScoredResult.Builder srBuilder = ScoredResult.newBuilder();
		String uniqueId = d.get(ZuliaConstants.ID_FIELD);

		if (!highlighterList.isEmpty() && !FetchType.FULL.equals(resultFetchType)) {
			throw new Exception("Highlighting requires a full fetch of the document");
		}

		if (!analysisHandlerList.isEmpty() && !FetchType.FULL.equals(resultFetchType)) {
			throw new Exception("Analysis requires a full fetch of the document");
		}

		if (!FetchType.NONE.equals(resultFetchType)) {
			handleStoredDoc(srBuilder, uniqueId, d, resultFetchType, fieldsToReturn, fieldsToMask, highlighterList, analysisHandlerList);
		}

		srBuilder.setScore(results[i].score);

		srBuilder.setUniqueId(uniqueId);

		srBuilder.setTimestamp(timestamp);

		srBuilder.setLuceneShardId(luceneShardId);
		srBuilder.setShard(shardNumber);
		srBuilder.setIndexName(indexName);
		srBuilder.setResultIndex(i);

		if (sorting) {
			handleSortValues(sortRequest, results[i], srBuilder);
		}
		return srBuilder;
	}

	private void handleStoredDoc(ScoredResult.Builder srBuilder, String uniqueId, Document d, FetchType resultFetchType, List<String> fieldsToReturn,
			List<String> fieldsToMask, List<ZuliaHighlighter> highlighterList, List<AnalysisHandler> analysisHandlerList) {

		ResultDocument.Builder rdBuilder = ResultDocument.newBuilder();
		rdBuilder.setUniqueId(uniqueId);
		rdBuilder.setIndexName(indexName);

		if (FetchType.FULL.equals(resultFetchType) || FetchType.META.equals(resultFetchType)) {
			BytesRef metaRef = d.getBinaryValue(ZuliaConstants.STORED_META_FIELD);
			org.bson.Document metaMongoDoc = new org.bson.Document();
			metaMongoDoc.putAll(ZuliaUtil.byteArrayToMongoDocument(metaRef.bytes));

			for (String key : metaMongoDoc.keySet()) {
				rdBuilder.addMetadata(Metadata.newBuilder().setKey(key).setValue(((String) metaMongoDoc.get(key))));
			}
		}

		if (FetchType.FULL.equals(resultFetchType)) {
			BytesRef docRef = d.getBinaryValue(ZuliaConstants.STORED_DOC_FIELD);
			if (docRef != null) {
				rdBuilder.setDocument(ByteString.copyFrom(docRef.bytes));
			}
		}

		ResultDocument resultDocument = rdBuilder.build();

		if (!highlighterList.isEmpty() || !analysisHandlerList.isEmpty() || !fieldsToMask.isEmpty() || !fieldsToReturn.isEmpty()) {
			org.bson.Document mongoDoc = ResultHelper.getDocumentFromResultDocument(resultDocument);
			if (mongoDoc != null) {
				if (!highlighterList.isEmpty()) {
					handleHighlight(highlighterList, srBuilder, mongoDoc);
				}
				if (!analysisHandlerList.isEmpty()) {
					AnalysisHandler.handleDocument(mongoDoc, analysisHandlerList, srBuilder);
				}

				resultDocument = filterDocument(resultDocument, fieldsToReturn, fieldsToMask, mongoDoc);
			}
		}

		srBuilder.setResultDocument(resultDocument);
	}

	private void handleSortValues(SortRequest sortRequest, ScoreDoc scoreDoc, ScoredResult.Builder srBuilder) {
		FieldDoc result = (FieldDoc) scoreDoc;

		SortValues.Builder sortValues = SortValues.newBuilder();

		int c = 0;
		for (Object o : result.fields) {
			if (o == null) {
				sortValues.addSortValue(SortValue.newBuilder().setExists(false));
				continue;
			}

			FieldSort fieldSort = sortRequest.getFieldSort(c);
			String sortField = fieldSort.getSortField();

			FieldConfig.FieldType fieldTypeForSortField = indexConfig.getFieldTypeForSortField(sortField);

			SortValue.Builder sortValueBuilder = SortValue.newBuilder().setExists(true);
			if (FieldTypeUtil.isNumericOrDateFieldType(fieldTypeForSortField)) {
				if (FieldTypeUtil.isNumericIntFieldType(fieldTypeForSortField)) {
					sortValueBuilder.setIntegerValue((Integer) o);
				}
				else if (FieldTypeUtil.isNumericLongFieldType(fieldTypeForSortField)) {
					sortValueBuilder.setLongValue((Long) o);
				}
				else if (FieldTypeUtil.isNumericFloatFieldType(fieldTypeForSortField)) {
					sortValueBuilder.setFloatValue((Float) o);
				}
				else if (FieldTypeUtil.isNumericDoubleFieldType(fieldTypeForSortField)) {
					sortValueBuilder.setDoubleValue((Double) o);
				}
				else if (FieldTypeUtil.isDateFieldType(fieldTypeForSortField)) {
					sortValueBuilder.setDateValue((Long) o);
				}
			}
			else {
				BytesRef b = (BytesRef) o;
				sortValueBuilder.setStringValue(b.utf8ToString());
			}
			sortValues.addSortValue(sortValueBuilder);

			c++;
		}
		srBuilder.setSortValues(sortValues);
	}

	private void handleHighlight(List<ZuliaHighlighter> highlighterList, ScoredResult.Builder srBuilder, org.bson.Document doc) {

		for (ZuliaHighlighter highlighter : highlighterList) {
			HighlightRequest highlightRequest = highlighter.getHighlight();
			String indexField = highlightRequest.getField();
			String storedFieldName = indexConfig.getStoredFieldName(indexField);

			if (storedFieldName != null) {
				HighlightResult.Builder highLightResult = HighlightResult.newBuilder();
				highLightResult.setField(storedFieldName);

				Object storeFieldValues = ResultHelper.getValueFromMongoDocument(doc, storedFieldName);

				ZuliaUtil.handleLists(storeFieldValues, (value) -> {
					String content = value.toString();
					TokenStream tokenStream = shardWriteManager.getAnalyzer().tokenStream(indexField, content);

					try {
						TextFragment[] bestTextFragments = highlighter
								.getBestTextFragments(tokenStream, content, false, highlightRequest.getNumberOfFragments());
						for (TextFragment bestTextFragment : bestTextFragments) {
							if (bestTextFragment != null && bestTextFragment.getScore() > 0) {
								highLightResult.addFragments(bestTextFragment.toString());
							}
						}
					}
					catch (Exception e) {
						throw new RuntimeException(e);
					}

				});

				srBuilder.addHighlightResult(highLightResult);
			}

		}

	}

	public ResultDocument getSourceDocument(String uniqueId, FetchType resultFetchType, List<String> fieldsToReturn, List<String> fieldsToMask)
			throws Exception {

		ResultDocument rd = null;

		Query query = new TermQuery(new org.apache.lucene.index.Term(ZuliaConstants.ID_FIELD, uniqueId));

		ShardQueryResponse segmentResponse = this
				.queryShard(query, null, 1, null, null, null, null, resultFetchType, fieldsToReturn, fieldsToMask, Collections.emptyList(),
						Collections.emptyList(), false);

		List<ScoredResult> scoredResultList = segmentResponse.getScoredResultList();
		if (!scoredResultList.isEmpty()) {
			ScoredResult scoredResult = scoredResultList.iterator().next();
			if (scoredResult.hasResultDocument()) {
				rd = scoredResult.getResultDocument();
			}
		}

		if (rd != null) {
			if (!fieldsToMask.isEmpty() || !fieldsToReturn.isEmpty()) {
				org.bson.Document mongoDocument = ResultHelper.getDocumentFromResultDocument(rd);
				if (mongoDocument != null) {
					rd = filterDocument(rd, fieldsToReturn, fieldsToMask, mongoDocument);
				}
			}
			return rd;
		}

		ResultDocument.Builder rdBuilder = ResultDocument.newBuilder();
		rdBuilder.setUniqueId(uniqueId);
		rdBuilder.setIndexName(indexName);
		return rdBuilder.build();

	}

	private ResultDocument filterDocument(ResultDocument rd, List<String> fieldsToReturn, List<String> fieldsToMask, org.bson.Document mongoDocument) {

		ResultDocument.Builder resultDocBuilder = rd.toBuilder();

		if (!fieldsToReturn.isEmpty()) {
			for (String key : new ArrayList<>(mongoDocument.keySet())) {
				if (!fieldsToReturn.contains(key)) {
					mongoDocument.remove(key);
				}
			}
		}
		if (!fieldsToMask.isEmpty()) {
			for (String field : fieldsToMask) {
				mongoDocument.remove(field);
			}
		}

		ByteString document = ByteString.copyFrom(ZuliaUtil.mongoDocumentToByteArray(mongoDocument));
		resultDocBuilder.setDocument(document);

		return resultDocBuilder.build();

	}

	private void possibleCommit() throws IOException {
		lastChange = System.currentTimeMillis();

		long count = counter.incrementAndGet();
		if ((count % indexConfig.getIndexSettings().getShardCommitInterval()) == 0) {
			forceCommit();
		}

	}

	public void forceCommit() throws IOException {
		if (!primary) {
			throw new IllegalStateException("Cannot force commit from replica:  index <" + indexName + "> shard <" + shardNumber + ">");
		}

		LOG.info("Committing shard <" + shardNumber + "> for index <" + indexName + ">");
		long currentTime = System.currentTimeMillis();
		shardWriteManager.commit();
		shardReaderManager.maybeRefresh();

		lastCommit = currentTime;

	}

	public void doCommit() throws IOException {

		long currentTime = System.currentTimeMillis();

		Long lastCh = lastChange;
		// if changes since started

		if (lastCh != null) {
			if ((currentTime - lastCh) > (indexConfig.getIndexSettings().getIdleTimeWithoutCommit() * 1000)) {
				if ((lastCommit == null) || (lastCh > lastCommit)) {
					forceCommit();
				}
			}
		}
	}

	public void close() throws IOException {
		shardWriteManager.close();
	}

	public void index(String uniqueId, long timestamp, org.bson.Document mongoDocument, List<Metadata> metadataList) throws Exception {
		if (!primary) {
			throw new IllegalStateException("Cannot index document <" + uniqueId + "> from replica:  index <" + indexName + "> shard <" + shardNumber + ">");
		}

		shardWriteManager.indexDocument(uniqueId, timestamp, mongoDocument, metadataList);

		possibleCommit();
	}

	public void deleteDocument(String uniqueId) throws Exception {
		if (!primary) {
			throw new IllegalStateException("Cannot delete document <" + uniqueId + "> from replica:  index <" + indexName + "> shard <" + shardNumber + ">");
		}

		Term term = new Term(ZuliaConstants.ID_FIELD, uniqueId);
		shardWriteManager.deleteDocuments(term);
		possibleCommit();

	}

	public void optimize(int maxNumberSegments) throws IOException {
		if (!primary) {
			throw new IllegalStateException("Cannot optimize replica:  index <" + indexName + "> shard <" + shardNumber + ">");
		}

		lastChange = System.currentTimeMillis();
		shardWriteManager.forceMerge(maxNumberSegments);
		forceCommit();
	}

	public GetFieldNamesResponse getFieldNames() throws IOException {
		shardReaderManager.maybeRefreshBlocking();
		ShardReader shardReader = shardReaderManager.acquire();

		try {
			GetFieldNamesResponse.Builder builder = GetFieldNamesResponse.newBuilder();
			shardReader.getFields().forEach(builder::addFieldName);
			return builder.build();
		}
		finally {
			shardReaderManager.decRef(shardReader);
		}
	}

	public void clear() throws IOException {
		if (!primary) {
			throw new IllegalStateException("Cannot clear replica:  index <" + indexName + "> shard <" + shardNumber + ">");
		}

		shardWriteManager.deleteAll();
		forceCommit();
	}

	public GetTermsResponse getTerms(GetTermsRequest request) throws IOException {

		shardReaderManager.maybeRefreshBlocking();
		ShardReader shardReader = shardReaderManager.acquire();

		try {
			ShardTermsHandler shardTermsHandler = shardReader.getShardTermsHandler();
			return shardTermsHandler.handleShardTerms(request);

		}
		finally {
			shardReaderManager.decRef(shardReader);
		}

	}

	public ShardCountResponse getNumberOfDocs() throws IOException {

		shardReaderManager.maybeRefreshBlocking();
		ShardReader shardReader = shardReaderManager.acquire();

		try {
			int count = shardReader.numDocs();
			return ShardCountResponse.newBuilder().setNumberOfDocs(count).setShardNumber(shardNumber).build();
		}
		finally {
			shardReaderManager.decRef(shardReader);
		}

	}

}
