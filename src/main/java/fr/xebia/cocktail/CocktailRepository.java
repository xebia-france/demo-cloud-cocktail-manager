/*
 * Copyright 2008-2012 Xebia and the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package fr.xebia.cocktail;

import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.PreDestroy;
import javax.inject.Inject;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CommonsHttpSolrServer;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.SpellCheckResponse;
import org.apache.solr.client.solrj.response.SpellCheckResponse.Suggestion;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.Mongo;
import com.mongodb.MongoURI;
import com.mongodb.WriteConcern;
import com.mongodb.WriteResult;

/**
 * Repository for cocktails (MongoDB + Solr).
 * 
 * @author <a href="mailto:cleclerc@xebia.fr">Cyrille Le Clerc</a>
 */
@Repository
public class CocktailRepository {

    @VisibleForTesting
    protected static Function<DBObject, Cocktail> BSON_TO_COCKTAIL = new Function<DBObject, Cocktail>() {

        @Override
        public Cocktail apply(DBObject cocktailAsDbObject) {
            Cocktail cocktail = new Cocktail();
            cocktail.setObjectId((ObjectId) cocktailAsDbObject.get("_id"));
            cocktail.setName((String) cocktailAsDbObject.get("name"));
            cocktail.setInstructions((String) cocktailAsDbObject.get("instructions"));
            cocktail.setPhotoUrl((String) cocktailAsDbObject.get("photoUrl"));
            cocktail.setSourceUrl((String) cocktailAsDbObject.get("sourceUrl"));

            @SuppressWarnings("unchecked")
            List<DBObject> ingredientsAsDbObjects = (List<DBObject>) cocktailAsDbObject.get("ingredients");
            for (DBObject ingredientAsDbObject : ingredientsAsDbObjects) {
                Ingredient ingredient = new Ingredient((String) ingredientAsDbObject.get("quantity"),
                        (String) ingredientAsDbObject.get("name"));
                cocktail.getIngredients().add(ingredient);
            }

            @SuppressWarnings("unchecked")
            List<String> comments = (List<String>) cocktailAsDbObject.get("comments");
            if (comments != null) {
                cocktail.getComments().addAll(comments);
            }

            return cocktail;
        }
    };

    @VisibleForTesting
    protected static Function<Cocktail, DBObject> COCKTAIL_TO_BSON = new Function<Cocktail, DBObject>() {
        @Override
        public DBObject apply(Cocktail cocktail) {
            BasicDBObjectBuilder rootBuilder = BasicDBObjectBuilder.start();
            rootBuilder.add("_id", cocktail.getObjectId());
            rootBuilder.add("id", cocktail.getId());
            rootBuilder.add("name", cocktail.getName());
            rootBuilder.add("instructions", cocktail.getInstructions());
            rootBuilder.add("photoUrl", cocktail.getPhotoUrl());
            rootBuilder.add("sourceUrl", cocktail.getSourceUrl());

            BasicDBList ingredients = new BasicDBList();
            rootBuilder.add("ingredients", ingredients);
            for (Ingredient ingredient : cocktail.getIngredients()) {
                ingredients.add(BasicDBObjectBuilder.start().add("name", ingredient.getName()).add("quantity", ingredient.getQuantity())
                        .get());
            }

            BasicDBList comments = new BasicDBList();
            rootBuilder.add("comments", comments);
            comments.addAll(cocktail.getComments());

            DBObject root = rootBuilder.get();
            return root;
        }
    };

    @VisibleForTesting
    protected DBCollection cocktails;

    @VisibleForTesting
    protected DB db;

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    @VisibleForTesting
    protected Mongo mongo;

    @VisibleForTesting
    protected SolrServer solrServer;

    @Inject
    public CocktailRepository(@Value("${mongodb_uri}") String mongoUri, @Value("${solr_url}") String solrUri) throws UnknownHostException,
            MalformedURLException {
        MongoURI databaseUri = new MongoURI(mongoUri);
        mongo = new Mongo(databaseUri);

        db = mongo.getDB(databaseUri.getDatabase());
        if (!Strings.isNullOrEmpty(databaseUri.getUsername())) {
            db.authenticate(databaseUri.getUsername(), databaseUri.getPassword());
        }
        cocktails = db.getCollection("cocktails");

        solrServer = new CommonsHttpSolrServer(solrUri);

    }

    public boolean delete(Cocktail cocktail) {
        Preconditions.checkNotNull(cocktail.getObjectId(), "Given objectId must not be null in %s", cocktail);
        try {
            // SOLR
            UpdateResponse solrResponse = solrServer.deleteByQuery("id:" + cocktail.getId());
            logger.trace("solr.delete for {}: {}", cocktail, solrResponse);

            // MONGODB
            WriteResult mongoResult = cocktails.remove(BasicDBObjectBuilder.start().add("_id", cocktail.getObjectId()).get(),
                    WriteConcern.SAFE);
            logger.trace("mongo.remove for {}: {}", cocktail, mongoResult);

            return mongoResult.getN() > 0;
        } catch (Exception e) {
            throw new RuntimeException("Exception deleting " + cocktail, e);
        }
    }

    public Collection<Cocktail> find(@Nullable String ingredient, @Nullable String name) {

        List<String> solrQueryPredicates = Lists.newArrayList();
        if (!Strings.isNullOrEmpty(ingredient)) {
            solrQueryPredicates.add("ingredient:" + ingredient + "*");
        }
        if (!Strings.isNullOrEmpty(name)) {
            solrQueryPredicates.add("name:" + name + "*");
        }

        DBCursor cursor;
        if (solrQueryPredicates.isEmpty()) {
            cursor = this.cocktails.find().sort(new BasicDBObject("name", "{x : -1}"));

        } else {
            SolrQuery solrQuery = new SolrQuery();
            String query = Joiner.on(" AND ").join(solrQueryPredicates);
            solrQuery.setQuery(query);
            try {
                QueryResponse solrResponse = solrServer.query(solrQuery);
                logger.trace("Response for {}: {}", query, solrResponse);
                SolrDocumentList docs = solrResponse.getResults();
                if (docs.isEmpty()) {
                    return Collections.emptyList();
                }
                Iterable<String> cocktailIds = Iterables.transform(docs, SOLR_DOCUMENT_ID_EXTRACTOR);
                Iterable<DBObject> cocktailsIdsAsDbObjects = Iterables.transform(cocktailIds, BSON_OBJECT_ID_BUILDER);

                cursor = this.cocktails.find(new BasicDBObject("$or", cocktailsIdsAsDbObjects));

                // TODO : ordering

            } catch (SolrServerException e) {
                throw Throwables.propagate(e);
            }
        }

        return Lists.newArrayList(Iterables.transform(cursor, BSON_TO_COCKTAIL));
    }

    @VisibleForTesting
    protected static Function<String, DBObject> BSON_OBJECT_ID_BUILDER = new Function<String, DBObject>() {
        @Override
        public DBObject apply(String objectId) {
            return new BasicDBObject("_id", new ObjectId(objectId));
        }
    };

    @VisibleForTesting
    protected static Function<SolrDocument, String> SOLR_DOCUMENT_ID_EXTRACTOR = new Function<SolrDocument, String>() {
        @Override
        public String apply(SolrDocument solrDocument) {
            return (String) solrDocument.getFieldValue("id");
        }

    };

    public Cocktail get(String id) {
        DBObject dbObject = cocktails.findOne(BasicDBObjectBuilder.start().add("_id", new ObjectId(id)).get());
        return BSON_TO_COCKTAIL.apply(dbObject);
    }

    public void insert(Cocktail cocktail) {
        Preconditions.checkArgument(cocktail.getObjectId() == null, "Given objectId must be null in %s", cocktail);
        try {
            cocktail.setObjectId(ObjectId.get());

            // SOLR
            SolrInputDocument solrInputDocument = toSolrInputDocument(cocktail);
            UpdateRequest req = new UpdateRequest();
            req.setAction(org.apache.solr.client.solrj.request.AbstractUpdateRequest.ACTION.COMMIT, false, false);
            req.add(solrInputDocument);
            UpdateResponse solrResponse = req.process(solrServer);
            logger.trace("solr.add for {}: {}", cocktail, solrResponse);

            // MONGODB
            DBObject bson = COCKTAIL_TO_BSON.apply(cocktail);
            WriteResult mongoResult = cocktails.insert(bson, WriteConcern.SAFE);
            logger.trace("mongo.insert for {}: {}", cocktail, mongoResult);
        } catch (Exception e) {
            throw new RuntimeException("Exception updating " + cocktail, e);
        }

    }

    @PreDestroy
    public void preDestroy() {
        mongo.close();
    }

    public void purgeRepository() {
        try {
            // SOLR
            UpdateResponse solrResponse = solrServer.deleteByQuery("*:*");
            logger.trace("solr.delete all: {}", solrResponse);

            // MONGODB
            WriteResult mongoResult = cocktails.remove(BasicDBObjectBuilder.start().get(), WriteConcern.SAFE);
            logger.trace("mongo.remove all", mongoResult);
        } catch (Exception e) {
            throw new RuntimeException("Exception purging repository", e);
        }
    }

    public List<String> suggestCocktailIngredientWords(String query) {
        return suggestCocktailWord(query, "/suggest/ingredient");
    }

    public List<String> suggestCocktailNameWords(String query) {
        return suggestCocktailWord(query, "/suggest/name");
    }

    private List<String> suggestCocktailWord(String query, String solrQueryType) {
        query = Strings.nullToEmpty(query);
        if (query.length() < 2) {
            return Collections.emptyList();
        }

        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setQuery(query);
        solrQuery.setQueryType(solrQueryType);

        List<String> words = Lists.newArrayList();

        try {
            QueryResponse solrResponse = solrServer.query(solrQuery);
            logger.trace("Response for {}: {}", query, solrResponse);
            SpellCheckResponse resp = solrResponse.getSpellCheckResponse();
            List<Suggestion> suggestions = resp.getSuggestions();
            for (Suggestion suggestion : suggestions) {
                words.addAll(suggestion.getAlternatives());
            }
        } catch (SolrServerException e) {
            logger.warn("Silently skip solr exception and return empty result", e);
        }

        return words;
    }

    protected SolrInputDocument toSolrInputDocument(Cocktail cocktail) {
        SolrInputDocument cocktailSolrDoc = new SolrInputDocument();
        cocktailSolrDoc.addField("id", cocktail.getId());
        cocktailSolrDoc.addField("name", cocktail.getName());
        cocktailSolrDoc.addField("ingredient", cocktail.getIngredientNames());

        return cocktailSolrDoc;
    }

    public void update(Cocktail cocktail) {
        Preconditions.checkNotNull(cocktail.getObjectId(), "Given objectId must not be null in %s", cocktail);
        try {

            // SOLR
            SolrInputDocument solrInputDocument = toSolrInputDocument(cocktail);
            UpdateRequest req = new UpdateRequest();
            req.setAction(org.apache.solr.client.solrj.request.AbstractUpdateRequest.ACTION.COMMIT, false, false);
            req.add(solrInputDocument);
            UpdateResponse solrResponse = req.process(solrServer);
            logger.trace("solr.add for {}: {}", cocktail, solrResponse);

            // MONGODB
            DBObject root = CocktailRepository.COCKTAIL_TO_BSON.apply(cocktail);
            WriteResult mongoResult = cocktails.save(root, WriteConcern.SAFE);
            logger.trace("mongo.save for {}: {}", cocktail, mongoResult);
        } catch (Exception e) {
            throw new RuntimeException("Exception updating " + cocktail, e);
        }
    }
}
