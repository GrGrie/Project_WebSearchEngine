package com.grgrie;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.print.attribute.SupportedValuesAttribute;

/**
 * Hello world!
 *
 */
public class App 
{
    private ExecutorService executorService;
    private DBhandler dbHandler;
    private static List<String> handledOrVisitedURLs;
    private static String startURL;
    private int maxDepth;
    private int maxNumberOfDocuments;
    private static int numberOfCrawledDocuments = 0;
    private boolean isAllowedToLeaveDomain = true;


    private boolean gotInside = false;
    private int numberOfThreads = 4;
    
    public App(){
        init();
    }

    protected void init(){
        
        System.out.println("Initializing App");

        dbHandler = new DBhandler();
        handledOrVisitedURLs = new ArrayList<>();
        String databaseName = "dbis";
        if(!dbHandler.databaseExists(databaseName)){
            System.out.println( "Initializing DB" );
            dbHandler.createDatabase(databaseName);
            dbHandler = new DBhandler(databaseName);
        } else {
            System.out.println("Connecting to Database");
            dbHandler = new DBhandler(databaseName);
        }
    }

    protected void emptyDatabase(String databaseName){
        dbHandler.emptyDatabase(databaseName);
    }

    protected String readCLIinput(){
        Scanner scanner = new Scanner(System.in);
        System.out.println("Enter your query: ");
        String query = scanner.nextLine();
        scanner.close();
        return query;
    }

    protected void crawl(String startUrl){
        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
        CompletionService<Integer> completionService = new ExecutorCompletionService<Integer>(executorService);
        this.executorService = executorService;
        int currentNumberOfThreads = 0;
        this.startURL = startUrl;
        
        // Filling the queue — if the program wasn't started before, add start URL to the queue. In other case
        // (if the program has been lauched previously), add all unfinished pages to the queue and, in case
        // empty threads are left, add top pages to crawl to the queue
        Queue<String> urlQueue = new LinkedList<>();
        List<String> nonFinishedURLs = dbHandler.getTopNonFinishedCrawlingPages(numberOfThreads);
        if(nonFinishedURLs.size() > 0){
            for (String nonFinishedUrl : nonFinishedURLs) {
                urlQueue.add(nonFinishedUrl);
            }
            if(urlQueue.size() < numberOfThreads){
                int queueEmptynessNumber = numberOfThreads - urlQueue.size();
                for (String string : dbHandler.getTopNotVisitedPages(queueEmptynessNumber, isAllowedToLeaveDomain, startURL).keySet()) {
                    urlQueue.add(string);
                }
            }
        } else {
          urlQueue.add(startUrl);  
        }
        
        String url = "";
        
        while(!urlQueue.isEmpty()){
            
            while(currentNumberOfThreads < numberOfThreads && !urlQueue.isEmpty() && !handledOrVisitedURLs.contains(urlQueue.peek())){
                url = urlQueue.poll();
                if(isAllowedToLeaveDomain || (!isAllowedToLeaveDomain && Indexer.getDomainName(url).equals(Indexer.getDomainName(startUrl)))){
                    gotInside = true;
                    System.out.println("Submitting new task at " + url);
                    completionService.submit(new Crawler(url, maxDepth, maxNumberOfDocuments, isAllowedToLeaveDomain, dbHandler));
                    currentNumberOfThreads++;
                }
            }
            
            if(handledOrVisitedURLs.contains(urlQueue.peek()))
                urlQueue.poll();
        
            //if(gotInside){
                try{
                Future<Integer> resultFuture = completionService.take();
                numberOfCrawledDocuments++;
                handledOrVisitedURLs.add(url);
                Iterator<Map.Entry<String, Integer>> iterator;
                if(maxNumberOfDocuments - (numberOfCrawledDocuments + currentNumberOfThreads) >= numberOfThreads)
                    iterator = dbHandler.getTopNotVisitedPages(numberOfThreads, isAllowedToLeaveDomain, startURL).entrySet().iterator();
                else iterator = dbHandler.getTopNotVisitedPages(maxNumberOfDocuments - (numberOfCrawledDocuments + currentNumberOfThreads) + 1, isAllowedToLeaveDomain, startURL).entrySet().iterator();

                    while(iterator.hasNext() && urlQueue.size() < numberOfThreads){
                        Map.Entry<String, Integer> map = iterator.next();
                        url = map.getKey();
                        if(!urlQueue.contains(url)) urlQueue.add(url); 
                    }

                System.out.println("Thread " + resultFuture.get() + " finished, assigning new task");
                currentNumberOfThreads--;

                } catch (InterruptedException | ExecutionException e) {
                    e.printStackTrace();
                }
                gotInside = false;  
            //}
           
        }
        executorService.shutdown();
        try {
            executorService.awaitTermination(1000, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            System.out.println("|*| App.crawl.executorService.awaitTermination error |*|");
            e.printStackTrace();
        }
    }

    protected void updateTFIDF(){
        dbHandler.updateTFIDF();
        System.out.println("TFIDF successfully updated");
    }

    protected List<String> googleSearch(String query){
        if(startURL == null) init();
        
        List<String> findGoogle = new ArrayList<>();
        
        // Check if allowed to leave domain
        boolean isAllowedToLeaveDomain = true;
        String domain = "";
        if(query.contains("site:")){
            isAllowedToLeaveDomain = false;
            int startIndexOfSite = query.indexOf("site:");
            int endIndexOfSite = query.indexOf(" ", startIndexOfSite+1);
            domain = query.substring(startIndexOfSite+5, endIndexOfSite);
            query = query.replaceFirst("site:" + domain + " ", "");
        }

        List<String> searchStringList = queryToList(query);

        if(domain != ""){
            GoogleQuery googleQuery = new GoogleQuery(searchStringList);
            googleQuery.setDBhandler(dbHandler);
            try {
                findGoogle = googleQuery.executeDomainOnlyGoogleQuery(domain);
                return findGoogle;
            } catch (SQLException e) {
                System.out.println("|*| Error in app.googleSearch() in domain != null statement");
                e.printStackTrace();
            }
            
        }
    
        System.out.println("searchStringList size is " + searchStringList.size());
        GoogleQuery googleQuery = new GoogleQuery(searchStringList);
        googleQuery.setDBhandler(dbHandler);
        try {
            findGoogle = googleQuery.executeGoogleQuery();
            return findGoogle;
        } catch (SQLException e) {
            System.out.println("|*| Error in app.googleSearch() in googleQuery.executeGoogleQuery statement");
            e.printStackTrace();
        }
        return findGoogle;

    }

    protected List<String> googleSearch(String query, boolean includeTFIDF){
        init();
        
        List<String> findGoogle = new ArrayList<>();
        
        // Check if allowed to leave domain
        boolean isAllowedToLeaveDomain = true;
        String domain = "";
        if(query.contains("site:")){
            isAllowedToLeaveDomain = false;
            int startIndexOfSite = query.indexOf("site:");
            int endIndexOfSite = query.indexOf(" ", startIndexOfSite+1);
            domain = query.substring(startIndexOfSite+5, endIndexOfSite);
            query = query.replaceFirst("site:" + domain + " ", "");
        }

        List<String> searchStringList = queryToList(query);

        if(domain != ""){
            GoogleQuery googleQuery = new GoogleQuery(searchStringList);
            googleQuery.setDBhandler(dbHandler);
            try {
                findGoogle = googleQuery.executeDomainOnlyGoogleQuery(domain, includeTFIDF);
                return findGoogle;
            } catch (SQLException e) {
                System.out.println("|*| Error in app.googleSearch() in domain != null statement");
                e.printStackTrace();
            }
            
        }
    
        System.out.println("searchStringList size is " + searchStringList.size());
        GoogleQuery googleQuery = new GoogleQuery(searchStringList);
        googleQuery.setDBhandler(dbHandler);
        try {
            findGoogle = googleQuery.executeGoogleQuery(includeTFIDF);
            return findGoogle;
        } catch (SQLException e) {
            System.out.println("|*| Error in app.googleSearch() in googleQuery.executeGoogleQuery statement");
            e.printStackTrace();
        }
        return findGoogle;

    }

    protected List<String> queryToList(String query){
        List<String> searchStringList = new ArrayList<>();
        String[] searchStringArray = query.split(" ");
        for (String string : searchStringArray) {
            searchStringList.add(string);
        }
        return searchStringList;
    }

    protected int getNumberOfTermOccurances(String term){
        try {
            return dbHandler.getNumberOfTermOccurances(term);
        } catch (SQLException e) {
            System.out.println("|*| Error in App.getNumberOfTermIccurances. Returning -1 |*|");
            e.printStackTrace();
        }
        return -1;
    }

    protected int getTotalNumberOfTerms(){
        try {
            return dbHandler.getTotalNumberOfTerms();
        } catch (SQLException e) {
            System.out.println("|*| Error in App.getTotalNumberOfTerms(). Returning -1 |*|");
            e.printStackTrace();
        }
        return -1;
    }

    private int getNumberOfCrawledDocuments(){
        return dbHandler.getNumberOfVisitedURLs();
    }

    protected String success(){
        return "Successfully finished given task!";
    }

    public void setNumberOfThreads(int numberOfThreads){
        this.numberOfThreads = numberOfThreads;
    }

    public int getNumberOfThreads(){
        return this.numberOfThreads;
    }
    
    public void setMaxDepth(int maxDepth){
        this.maxDepth = maxDepth;
    }

    public int maxDepth(){
        return this.maxDepth;
    }

    public void setMaxNumberOfDocuments(int maxNumberOfDocuments){
        this.maxNumberOfDocuments = maxNumberOfDocuments;
    }

    public int maxNumberOfDocuments(){
        return this.maxNumberOfDocuments;
    }

    public boolean isAllowedToLeaveDomain() {
        return isAllowedToLeaveDomain;
    }

    public void setIsAllowedToLeaveDomain(boolean isAllowedToLeaveDomain) {
        this.isAllowedToLeaveDomain = isAllowedToLeaveDomain;
    }

    public void calculatePageRank(){
        PageRank pageRank = new PageRank(this.dbHandler);
        pageRank.calculatePageRank();
    }

    public void calculateBM25(String searchString){
        List<String> searchWords = Arrays.asList(searchString.split(" "));
        List<String> conjunctiveSearch = new ArrayList<>();
        String siteOperator = "";
        for (String string : searchWords) {
            if(string.startsWith("\"") && string.endsWith("\""))
                conjunctiveSearch.add(string);
            if(string.startsWith("site:"))
                siteOperator = string.substring(5);
        }
        List<String> docs;
        if(conjunctiveSearch.size() != 0) docs = dbHandler.calculateBM25(String.join(" ", conjunctiveSearch));
        else docs = dbHandler.calculateBM25(searchString);
        if(docs.size() ==  0){
            System.out.println("Words not found :c");
            return;
        }
        System.out.println("Your words found in the following links: ");
        for (String doc : docs) {
            if(!siteOperator.equals("")){
                if(doc.startsWith(siteOperator))
                    System.out.println(doc);
            } else {
                System.out.println();
            }
            
        }
    }

    public void calculateBM25Rank(String searchString){
        PageRank pageRank = new PageRank(this.dbHandler);
        pageRank.calculatePageRank();
        Map<Integer, Double> pageRanks = pageRank.getPageRankAndIDs();
        
        List<String> docs = dbHandler.calculateBM25Rank(searchString);
        if(docs.size() ==  0){
            System.out.println("Words not found :c");
            return;
        }
        System.out.println("Your words found in the following links: ");
        for (String doc : docs) {
            System.out.println(doc);
        }


    }

    public DBhandler getDBhandler(){
        return this.dbHandler;
    }
}


