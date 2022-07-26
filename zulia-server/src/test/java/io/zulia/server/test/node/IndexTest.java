package io.zulia.server.test.node;

import io.zulia.DefaultAnalyzers;
import io.zulia.client.command.UpdateIndex;
import io.zulia.client.config.ClientIndexConfig;
import io.zulia.client.pool.ZuliaWorkPool;
import io.zulia.client.result.GetIndexConfigResult;
import io.zulia.client.result.UpdateIndexResult;
import io.zulia.fields.FieldConfigBuilder;
import io.zulia.message.ZuliaIndex;
import io.zulia.message.ZuliaIndex.AnalyzerSettings.Filter;
import io.zulia.message.ZuliaIndex.IndexSettings;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Collections;
import java.util.List;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class IndexTest {

	public static final String INDEX_TEST = "indexTest";

	private static ZuliaWorkPool zuliaWorkPool;

	@BeforeAll
	public static void init() {

	}

	@Test
	@Order(1)
	public void createIndex() throws Exception {

		TestHelper.createNodes(3);

		TestHelper.startNodes();

		Thread.sleep(2000);

		zuliaWorkPool = TestHelper.createClient();

		{
			ClientIndexConfig indexConfig = new ClientIndexConfig();
			indexConfig.addDefaultSearchField("title");
			indexConfig.addFieldConfig(FieldConfigBuilder.createString("id").indexAs(DefaultAnalyzers.LC_KEYWORD).sort());
			indexConfig.addFieldConfig(FieldConfigBuilder.createString("title").indexAs(DefaultAnalyzers.STANDARD, "myTitle").sortAs("mySortTitle"));
			indexConfig.addFieldConfig(
					FieldConfigBuilder.createString("category").indexAs(DefaultAnalyzers.STANDARD).indexAs(DefaultAnalyzers.KEYWORD, "categoryExact")
							.sortAs(ZuliaIndex.SortAs.StringHandling.FOLDING, "categoryFolded").sort());
			indexConfig.addFieldConfig(
					FieldConfigBuilder.createDouble("rating").index().sort().description("Some optional description").displayName("Product Rating"));
			indexConfig.setIndexName(INDEX_TEST);
			indexConfig.setNumberOfShards(1);

			zuliaWorkPool.createIndex(indexConfig);
		}

		{
			Assertions.assertThrows(Exception.class, () -> {
				@SuppressWarnings("unused") GetIndexConfigResult indexConfigFromServer = zuliaWorkPool.getIndexConfig("made up");
			}, "Index <made up> does not exist");
		}

		{

			ClientIndexConfig indexConfigFromServer = zuliaWorkPool.getIndexConfig(INDEX_TEST).getIndexConfig();

			Assertions.assertEquals(4, indexConfigFromServer.getFieldConfigMap().size());

			ZuliaIndex.FieldConfig idFieldConfig = indexConfigFromServer.getFieldConfig("id");
			Assertions.assertTrue(idFieldConfig.getFacetAsList().isEmpty());
			Assertions.assertEquals(1, idFieldConfig.getSortAsCount());
			Assertions.assertEquals("id", idFieldConfig.getSortAsList().get(0).getSortFieldName());
			Assertions.assertEquals(1, idFieldConfig.getIndexAsCount());
			Assertions.assertEquals("id", idFieldConfig.getIndexAsList().get(0).getIndexFieldName());
			Assertions.assertEquals(DefaultAnalyzers.LC_KEYWORD, idFieldConfig.getIndexAsList().get(0).getAnalyzerName());

			ZuliaIndex.FieldConfig titleFieldConfig = indexConfigFromServer.getFieldConfig("title");
			Assertions.assertTrue(titleFieldConfig.getFacetAsList().isEmpty());
			Assertions.assertEquals(1, titleFieldConfig.getSortAsCount());
			Assertions.assertEquals("mySortTitle", titleFieldConfig.getSortAsList().get(0).getSortFieldName());
			Assertions.assertEquals(1, titleFieldConfig.getIndexAsCount());
			Assertions.assertEquals("myTitle", titleFieldConfig.getIndexAsList().get(0).getIndexFieldName());
			Assertions.assertEquals(DefaultAnalyzers.STANDARD, titleFieldConfig.getIndexAsList().get(0).getAnalyzerName());

			ZuliaIndex.FieldConfig categoryFieldConfig = indexConfigFromServer.getFieldConfig("category");
			Assertions.assertTrue(categoryFieldConfig.getFacetAsList().isEmpty());
			Assertions.assertEquals(2, categoryFieldConfig.getSortAsCount());
			Assertions.assertEquals("categoryFolded", categoryFieldConfig.getSortAsList().get(0).getSortFieldName());
			Assertions.assertEquals("category", categoryFieldConfig.getSortAsList().get(1).getSortFieldName());
			Assertions.assertEquals(2, categoryFieldConfig.getIndexAsCount());
			Assertions.assertEquals("category", categoryFieldConfig.getIndexAsList().get(0).getIndexFieldName());
			Assertions.assertEquals(DefaultAnalyzers.STANDARD, categoryFieldConfig.getIndexAsList().get(0).getAnalyzerName());
			Assertions.assertEquals("categoryExact", categoryFieldConfig.getIndexAsList().get(1).getIndexFieldName());
			Assertions.assertEquals(DefaultAnalyzers.KEYWORD, categoryFieldConfig.getIndexAsList().get(1).getAnalyzerName());

			ZuliaIndex.FieldConfig ratingField = indexConfigFromServer.getFieldConfig("rating");
			Assertions.assertTrue(ratingField.getFacetAsList().isEmpty());
			Assertions.assertEquals(1, ratingField.getSortAsCount());
			Assertions.assertEquals("rating", ratingField.getSortAsList().get(0).getSortFieldName());
			Assertions.assertEquals(1, ratingField.getIndexAsCount());
			Assertions.assertEquals("rating", ratingField.getIndexAsList().get(0).getIndexFieldName());
			Assertions.assertEquals("", ratingField.getIndexAsList().get(0).getAnalyzerName());
			Assertions.assertEquals("Some optional description", ratingField.getDescription());
			Assertions.assertEquals("Product Rating", ratingField.getDisplayName());

			List<String> defaultSearchFields = indexConfigFromServer.getDefaultSearchFields();
			Assertions.assertEquals(1, defaultSearchFields.size());
			Assertions.assertEquals("title", defaultSearchFields.get(0));
		}

		{
			ClientIndexConfig indexConfig = new ClientIndexConfig();
			indexConfig.addDefaultSearchFields("title", "category");
			indexConfig.addFieldConfig(FieldConfigBuilder.createString("id").indexAs(DefaultAnalyzers.LC_KEYWORD).sort());
			indexConfig.addFieldConfig(FieldConfigBuilder.createString("title").indexAs(DefaultAnalyzers.STANDARD, "myTitle").sortAs("mySortTitle"));
			indexConfig.addFieldConfig(
					FieldConfigBuilder.createDouble("rating").index().sort().description("Some optional description").displayName("Product Rating"));
			indexConfig.setIndexName(INDEX_TEST);
			indexConfig.setNumberOfShards(1);

			zuliaWorkPool.createIndex(indexConfig);

		}

		{
			ClientIndexConfig indexConfigFromServer = zuliaWorkPool.getIndexConfig(INDEX_TEST).getIndexConfig();
			Assertions.assertEquals(indexConfigFromServer.getFieldConfigMap().size(), 3);

			List<String> defaultSearchFields = indexConfigFromServer.getDefaultSearchFields();
			Assertions.assertEquals(2, defaultSearchFields.size());
			Assertions.assertEquals("title", defaultSearchFields.get(0));
			Assertions.assertEquals("category", defaultSearchFields.get(1));
		}

	}

	@Test
	@Order(2)
	public void updateIndex() throws Exception {
		{
			UpdateIndex updateIndex = new UpdateIndex(INDEX_TEST);
			updateIndex.setIndexWeight(4);

			FieldConfigBuilder newField = FieldConfigBuilder.createString("newField").indexAs(DefaultAnalyzers.LC_KEYWORD).sort();
			updateIndex.mergeFieldConfig(newField);

			zuliaWorkPool.updateIndex(updateIndex);

		}

		{

			ClientIndexConfig indexConfigFromServer = zuliaWorkPool.getIndexConfig(INDEX_TEST).getIndexConfig();

			Assertions.assertEquals(4, indexConfigFromServer.getIndexWeight());
			Assertions.assertEquals(4, indexConfigFromServer.getFieldConfigMap().size());
			ZuliaIndex.FieldConfig newField = indexConfigFromServer.getFieldConfig("newField");
			Assertions.assertEquals(1, newField.getSortAsCount());
			Assertions.assertEquals("newField", newField.getSortAsList().get(0).getSortFieldName());
			Assertions.assertEquals(1, newField.getIndexAsCount());
			Assertions.assertEquals("newField", newField.getIndexAsList().get(0).getIndexFieldName());
			Assertions.assertEquals(DefaultAnalyzers.LC_KEYWORD, newField.getIndexAsList().get(0).getAnalyzerName());
			Assertions.assertEquals(new Document(), indexConfigFromServer.getMeta());
		}

		{
			UpdateIndex updateIndex = new UpdateIndex(INDEX_TEST);
			updateIndex.mergeMetadata(new Document().append("someKey", 5).append("otherKey", "a string"));

			FieldConfigBuilder newField2 = FieldConfigBuilder.createString("newField2").indexAs(DefaultAnalyzers.STANDARD).sort();
			updateIndex.replaceFieldConfig(newField2);

			zuliaWorkPool.updateIndex(updateIndex);

		}

		{

			ClientIndexConfig indexConfigFromServer = zuliaWorkPool.getIndexConfig(INDEX_TEST).getIndexConfig();

			Assertions.assertEquals(1, indexConfigFromServer.getFieldConfigMap().size());
			ZuliaIndex.FieldConfig newField = indexConfigFromServer.getFieldConfig("newField2");
			Assertions.assertEquals(1, newField.getSortAsCount());
			Assertions.assertEquals("newField2", newField.getSortAsList().get(0).getSortFieldName());
			Assertions.assertEquals(1, newField.getIndexAsCount());
			Assertions.assertEquals("newField2", newField.getIndexAsList().get(0).getIndexFieldName());
			Assertions.assertEquals(DefaultAnalyzers.STANDARD, newField.getIndexAsList().get(0).getAnalyzerName());
			Document meta = indexConfigFromServer.getMeta();
			Assertions.assertEquals(5, meta.getInteger("someKey"));
			Assertions.assertEquals("a string", meta.getString("otherKey"));
		}

		{
			UpdateIndex updateIndex = new UpdateIndex(INDEX_TEST);
			updateIndex.mergeMetadata(new Document().append("someKey", 10));
			updateIndex.removeMetadataByKey(List.of("otherKey"));

			FieldConfigBuilder myField = FieldConfigBuilder.createString("myField").indexAs(DefaultAnalyzers.STANDARD).sort();
			FieldConfigBuilder otherField = FieldConfigBuilder.createString("otherField").indexAs(DefaultAnalyzers.LC_KEYWORD).sort();
			updateIndex.mergeFieldConfig(myField, otherField);

			zuliaWorkPool.updateIndex(updateIndex);

		}

		{

			ClientIndexConfig indexConfigFromServer = zuliaWorkPool.getIndexConfig(INDEX_TEST).getIndexConfig();

			Assertions.assertEquals(3, indexConfigFromServer.getFieldConfigMap().size());

			Document meta = indexConfigFromServer.getMeta();
			Assertions.assertEquals(10, meta.getInteger("someKey"));
			Assertions.assertNull(meta.get("otherKey"));
		}

		{
			UpdateIndex updateIndex = new UpdateIndex(INDEX_TEST);
			updateIndex.replaceMetadata(new Document().append("stuff", "for free"));

			FieldConfigBuilder myField = FieldConfigBuilder.createString("myField").indexAs(DefaultAnalyzers.STANDARD).sort();
			FieldConfigBuilder otherField = FieldConfigBuilder.createString("otherField").indexAs(DefaultAnalyzers.LC_KEYWORD).sort();
			updateIndex.mergeFieldConfig(myField, otherField);

			zuliaWorkPool.updateIndex(updateIndex);

		}

		{
			ClientIndexConfig indexConfigFromServer = zuliaWorkPool.getIndexConfig(INDEX_TEST).getIndexConfig();

			Assertions.assertEquals(3, indexConfigFromServer.getFieldConfigMap().size());

			Document meta = indexConfigFromServer.getMeta();
			Assertions.assertEquals("for free", meta.getString("stuff"));
			Assertions.assertNull(meta.get("otherKey"));
			Assertions.assertNull(meta.get("someKey"));

			Assertions.assertEquals(0, indexConfigFromServer.getAnalyzerSettingsMap().size());
		}

		{

			UpdateIndex updateIndex = new UpdateIndex(INDEX_TEST);
			FieldConfigBuilder myField = FieldConfigBuilder.createString("myField").indexAs("custom").sort();
			updateIndex.mergeFieldConfig(myField);

			Assertions.assertThrows(Exception.class, () -> {
				zuliaWorkPool.updateIndex(updateIndex);
			}, "Analyzer <custom> is not a default analyzer and is not given as a custom analyzer for field <myField> indexed as <myField>");

			ZuliaIndex.AnalyzerSettings custom = ZuliaIndex.AnalyzerSettings.newBuilder().setName("custom").addFilter(Filter.LOWERCASE).build();
			ZuliaIndex.AnalyzerSettings mine = ZuliaIndex.AnalyzerSettings.newBuilder().setName("mine").addFilter(Filter.LOWERCASE).addFilter(Filter.BRITISH_US)
					.build();
			updateIndex.mergeAnalyzerSettings(custom, mine);
			zuliaWorkPool.updateIndex(updateIndex);
		}

		{
			ClientIndexConfig indexConfigFromServer = zuliaWorkPool.getIndexConfig(INDEX_TEST).getIndexConfig();

			Assertions.assertEquals(2, indexConfigFromServer.getAnalyzerSettingsMap().size());

			ZuliaIndex.AnalyzerSettings custom = indexConfigFromServer.getAnalyzerSettings("custom");
			Assertions.assertEquals("custom", custom.getName());
			Assertions.assertEquals(1, custom.getFilterCount());
			Assertions.assertEquals(Filter.LOWERCASE, custom.getFilterList().get(0));

			ZuliaIndex.AnalyzerSettings mine = indexConfigFromServer.getAnalyzerSettings("mine");
			Assertions.assertEquals("mine", mine.getName());
			Assertions.assertEquals(2, mine.getFilterCount());
			Assertions.assertEquals(Filter.LOWERCASE, mine.getFilterList().get(0));
			Assertions.assertEquals(Filter.BRITISH_US, mine.getFilterList().get(1));
		}

		{

			UpdateIndex updateIndex = new UpdateIndex(INDEX_TEST);
			ZuliaIndex.AnalyzerSettings custom = ZuliaIndex.AnalyzerSettings.newBuilder().setName("custom").addFilter(Filter.LOWERCASE)
					.addFilter(Filter.ASCII_FOLDING).build();
			updateIndex.mergeAnalyzerSettings(custom);
			zuliaWorkPool.updateIndex(updateIndex);
		}

		{
			ClientIndexConfig indexConfigFromServer = zuliaWorkPool.getIndexConfig(INDEX_TEST).getIndexConfig();

			Assertions.assertEquals(2, indexConfigFromServer.getAnalyzerSettingsMap().size());

			ZuliaIndex.AnalyzerSettings custom = indexConfigFromServer.getAnalyzerSettings("custom");
			Assertions.assertEquals("custom", custom.getName());
			Assertions.assertEquals(2, custom.getFilterCount());
			Assertions.assertEquals(Filter.LOWERCASE, custom.getFilterList().get(0));
			Assertions.assertEquals(Filter.ASCII_FOLDING, custom.getFilterList().get(1));

			ZuliaIndex.AnalyzerSettings mine = indexConfigFromServer.getAnalyzerSettings("mine");
			Assertions.assertEquals("mine", mine.getName());
			Assertions.assertEquals(2, mine.getFilterCount());
			Assertions.assertEquals(Filter.LOWERCASE, mine.getFilterList().get(0));
			Assertions.assertEquals(Filter.BRITISH_US, mine.getFilterList().get(1));
		}

		{

			UpdateIndex updateIndex = new UpdateIndex(INDEX_TEST);
			updateIndex.removeAnalyzerSettingsByName(Collections.singleton("mine"));
			zuliaWorkPool.updateIndex(updateIndex);
		}

		{

			UpdateIndex updateIndex = new UpdateIndex(INDEX_TEST);
			updateIndex.removeAnalyzerSettingsByName(Collections.singleton("custom"));

			Assertions.assertThrows(Exception.class, () -> {
				zuliaWorkPool.updateIndex(updateIndex);
			}, "Analyzer <custom> is not a default analyzer and is not given as a custom analyzer for field <myField> indexed as <myField>");
		}

		{
			UpdateIndex updateIndex = new UpdateIndex(INDEX_TEST);
			updateIndex.removeAnalyzerSettingsByName(Collections.singleton("custom"));
			FieldConfigBuilder myField = FieldConfigBuilder.createString("myField").indexAs(DefaultAnalyzers.STANDARD).sort();
			updateIndex.mergeFieldConfig(myField);
			UpdateIndexResult updateIndexResult = zuliaWorkPool.updateIndex(updateIndex);

			IndexSettings indexSettings = updateIndexResult.getFullIndexSettings();
			Assertions.assertEquals(3, indexSettings.getFieldConfigList().size());
			Assertions.assertEquals(0, indexSettings.getAnalyzerSettingsCount());
			Assertions.assertEquals(4, indexSettings.getIndexWeight());
		}

	}

	@AfterAll
	public static void shutdown() throws Exception {
		TestHelper.stopNodes();
		zuliaWorkPool.shutdown();
	}
}