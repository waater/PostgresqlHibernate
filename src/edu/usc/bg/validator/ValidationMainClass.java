/**                                                                                                                                                                                
 * Copyright (c) 2012 USC Database Laboratory All rights reserved. 
 *
 * Authors:  Sumita Barahmand and Shahram Ghandeharizadeh                                                                                                                            
 *                                                                                                                                                                                 
 * Licensed under the Apache License, Version 2.0 (the "License"); you                                                                                                             
 * may not use this file except in compliance with the License. You                                                                                                                
 * may obtain a copy of the License at                                                                                                                                             
 *                                                                                                                                                                                 
 * http://www.apache.org/licenses/LICENSE-2.0                                                                                                                                      
 *                                                                                                                                                                                 
 * Unless required by applicable law or agreed to in writing, software                                                                                                             
 * distributed under the License is distributed on an "AS IS" BASIS,                                                                                                               
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or                                                                                                                 
 * implied. See the License for the specific language governing                                                                                                                    
 * permissions and limitations under the License. See accompanying                                                                                                                 
 * LICENSE file.                                                                                                                                                                   
 */


package edu.usc.bg.validator;


import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;


import edu.usc.bg.base.Client;
import edu.usc.bg.workloads.CoreWorkload;

/**
 * Reads the log record files and assigns them to the validation threads for being processed
 * merges the stats it gets from the validation threads
 * @author barahman
 *
 */
class ValidationStatusThread extends Thread{
	TotalValidationThreadResults _finalRes;
	boolean timeToStop = false;

	public boolean getTimeToStop(){
		return timeToStop;
	}
	public void setTimeToStop(){
		timeToStop = true;
	}

	ValidationStatusThread(TotalValidationThreadResults finalRes){
		_finalRes = finalRes;
	}

	public void run(){
		int count = 0;
		while(!timeToStop){
			try {
				sleep(10000);
				count++;
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			System.out.println("\t-- "+(count*10)+" secs: Reads are still being validated... NumReadOpsProcessed till now:"+_finalRes.getNumReadOpsProcessed());
			System.out.println("\t-- "+(count*10)+" secs: Reads are still being validated... NumPruned till now:"+_finalRes.getPruned());
		}
	}
}


class TotalValidationThreadResults{
	AtomicInteger numReadOpsProcessed = null;
	AtomicInteger numStaleReadsreturned = null;
	AtomicInteger prunedReads = null;

	public int getNumReadOpsProcessed() {
		return numReadOpsProcessed.get();
	}
	public int getNumStaleReadsreturned() {
		return numStaleReadsreturned.get();
	}
	public int getPruned() {
		return prunedReads.get();
	}
	
	public void incPruned() {
		int v;
        do {
            v = prunedReads.get();
        } while (!prunedReads.compareAndSet(v, v + 1));
	}
	public void incNumStaleReadsreturned() {
		int v;
        do {
            v = numStaleReadsreturned.get();
        } while (!numStaleReadsreturned.compareAndSet(v, v + 1));
	}
	public void incNumReadOpsProcessed() {
        int v;
        do {
            v = numReadOpsProcessed.get();
        } while (!numReadOpsProcessed.compareAndSet(v, v + 1));
    }
	
	public TotalValidationThreadResults(){
		if(numReadOpsProcessed == null){
			numReadOpsProcessed = new AtomicInteger();
			numReadOpsProcessed.set(0);
		}
		if(numStaleReadsreturned == null){
			numStaleReadsreturned = new AtomicInteger();
			numStaleReadsreturned.set(0);
		}
		if(prunedReads == null){
			prunedReads = new AtomicInteger();
			prunedReads.set(0);
		}
	}
	
}


public class ValidationMainClass{

	private static final boolean verbose = true;

	public static final String DB_TENANT_PROPERTY = "tenant";
	public static final String DB_TENANT_PROPERTY_DEFAULT = "single"; //or multi
	public static final String VALIDATION_THREADS_PROPERTY = "validationthreads";
	public static final String VALIDATION_THREADS_PROPERTY_DEFAULT = "100";
	public static final String VALIDATION_BLOCK_PROPERTY = "validationblock";
	public static final String VALIDATION_BLOCK_PROPERTY_DEFAULT = "10000";
	public static final String VALIDATION_APPROACH_PROPERTY = "validationapproach";
	public static final String VALIDATION_APPROACH_PROPERTY_DEFAULT = "interval"; //or RDBMS
	public static final String VALIDATION_DBURL_PROPERTY = "validation.url";
	public static final String VALIDATION_DBURL_PROPERTY_DEFAULT = "jdbc:oracle:thin:@localhost:1521:orcl";
	public static final String VALIDATION_DBUSER_PROPERTY = "validation.user";
	public static final String VALIDATION_DBUSER_PROPERTY_DEFAULT = "benchmark";
	public static final String VALIDATION_DBPWD_PROPERTY = "validation.passwd";
	public static final String VALIDATION_DBPWD_PROPERTY_DEFAULT = "111111";
	public static final String VALIDATION_DBDRIVER_PROPERTY = "validation.driver";
	public static final String VALIDATION_DBDRIVER_PROPERTY_DEFAULT = "oracle.jdbc.driver.OracleDriver";


	public static void buildValidationIndexes( Properties props) {
		String url = props.getProperty(VALIDATION_DBURL_PROPERTY,
				VALIDATION_DBURL_PROPERTY_DEFAULT);
		String user = props.getProperty(VALIDATION_DBUSER_PROPERTY, VALIDATION_DBUSER_PROPERTY_DEFAULT);
		String passwd = props.getProperty(VALIDATION_DBPWD_PROPERTY, VALIDATION_DBPWD_PROPERTY_DEFAULT);
		String driver = props.getProperty(VALIDATION_DBDRIVER_PROPERTY,
				VALIDATION_DBDRIVER_PROPERTY_DEFAULT);

		Connection conn = null;
		Statement stmt = null;
		int machineid =Integer.parseInt(props.getProperty(Client.MACHINE_ID_PROPERTY, Client.MACHINE_ID_PROPERTY_DEFAULT));

		try {
			Class.forName(driver);
			conn = DriverManager.getConnection(url, user, passwd);
		} catch (Exception e) {
			e.printStackTrace(System.out);
		}

		try {
			int count = 1;
			if(props.getProperty(DB_TENANT_PROPERTY,DB_TENANT_PROPERTY_DEFAULT).equalsIgnoreCase("single"))
				count = 1;
			else 
				count = Integer.parseInt(props.getProperty(Client.THREAD_CNT_PROPERTY, Client.THREAD_CNT_PROPERTY_DEFAULT));

			stmt = conn.createStatement();
			long startIdx = System.currentTimeMillis();
			for(int i=1; i<=count; i++){
				dropIndex(stmt, "TUPDATE"+machineid+"c"+i+"_IDX$$_start");
				dropIndex(stmt, "TUPDATE"+machineid+"c"+i+"_IDX$$_end");
				dropIndex(stmt, "TUPDATE"+machineid+"c"+i+"_IDX$$_resource");
				dropIndex(stmt, "TUPDATE"+machineid+"c"+i+"_IDX$$_optype");

				stmt.executeUpdate("CREATE INDEX TUPDATE"+machineid+"c"+i+"_IDX$$_start ON TUPDATE"+machineid+"c"+i+" (STARTTIME)"
						+ "COMPUTE STATISTICS NOLOGGING");
				stmt.executeUpdate("CREATE INDEX TUPDATE"+machineid+"c"+i+"_IDX$$_end ON TUPDATE"+machineid+"c"+i+" (ENDTIME)"
						+ "COMPUTE STATISTICS NOLOGGING");
				stmt.executeUpdate("CREATE INDEX TUPDATE"+machineid+"c"+i+"_IDX$$_resource ON TUPDATE"+machineid+"c"+i+" (RID)"
						+ "COMPUTE STATISTICS NOLOGGING");
				stmt.executeUpdate("CREATE INDEX TUPDATE"+machineid+"c"+i+"_IDX$$_optype ON TUPDATE"+machineid+"c"+i+" (OPTYPE)"
						+ "COMPUTE STATISTICS NOLOGGING");

				stmt.executeUpdate("analyze table tupdate"+machineid+"c"+i+" compute statistics");
				long endIdx = System.currentTimeMillis();
				System.out.println("\t Time to build validation index for" +machineid+" structures(ms):"
						+ (endIdx - startIdx));

			}


		} catch (Exception e) {
			e.printStackTrace(System.out);
		} finally {
			try {
				if (stmt != null)
					stmt.close();
				if (conn != null)
					conn.close();
			} catch (SQLException e) {
				e.printStackTrace(System.out);
			}
		}

	}



	public static int readUpdateFiles(Properties props, ConcurrentHashMap<String, resourceUpdateStat> updateStats, String dir){
		FileInputStream fstream = null;
		DataInputStream in = null;
		BufferedReader br = null;
		//if single tenant they will all dump into tread1 and tupdate1
		//else each will dump into their own table  
		int numTotalUpdates = 0;
		int machineid =Integer.parseInt(props.getProperty(Client.MACHINE_ID_PROPERTY, Client.MACHINE_ID_PROPERTY_DEFAULT));
		int threadCount = Integer.parseInt(props.getProperty(Client.THREAD_CNT_PROPERTY, Client.THREAD_CNT_PROPERTY_DEFAULT));
		int vThreads = Integer.parseInt(props.getProperty(VALIDATION_THREADS_PROPERTY,
				VALIDATION_THREADS_PROPERTY_DEFAULT));
		int vBlock = Integer.parseInt(props.getProperty(VALIDATION_BLOCK_PROPERTY,
				VALIDATION_BLOCK_PROPERTY_DEFAULT));
		Vector<logObject> updatesToBeProcessed = new Vector<logObject>();
		String approach = props.getProperty(VALIDATION_APPROACH_PROPERTY,
				VALIDATION_APPROACH_PROPERTY_DEFAULT);
		Semaphore semaphore = new Semaphore(vThreads);
		Semaphore putSemaphore = new Semaphore(1);

		if(approach.equalsIgnoreCase("RDBMS")){
			//create the schema needed for validation
			ValidationMainClass.createValidationSchema(props);	
		}
		Vector<UpdateProcessorThread> uThreads = new Vector<UpdateProcessorThread>();
		//read the update files for all the threads
		for(int i=0; i< threadCount; i++){
			String line;
			String[] tokens;
			try {
				fstream = new FileInputStream(dir+"//update"+machineid+"-"+i + ".txt");
				in = new DataInputStream(fstream);
				br = new BufferedReader(new InputStreamReader(in));
			} catch (FileNotFoundException e) {
				e.printStackTrace(System.out);
			}

			try {
				while ((line = br.readLine()) != null) {
					numTotalUpdates++;
					tokens = line.split(",");
					logObject record = new logObject(tokens[0], tokens[1], tokens[2],tokens[3], tokens[4], tokens[5], tokens[6], tokens[7], tokens[8]);
					updatesToBeProcessed.add(record);
					if(updatesToBeProcessed.size() == vBlock){
						semaphore.acquire();
						//create thread to process the update records
						UpdateProcessorThread upThread = new UpdateProcessorThread(props, updateStats, updatesToBeProcessed, semaphore, putSemaphore);
						uThreads.add(upThread);
						semaphore.release();
						upThread.start();
						updatesToBeProcessed = new Vector<logObject>();		
					}	
				}

			}catch (Exception e) {
				e.printStackTrace(System.out);
			} 
			try {
				if(in != null)	in.close();
				if(br != null) br.close();	
			} catch (IOException e) {
				e.printStackTrace(System.out);
			}	

		}
		//create a thread to process the remaining ones
		if(updatesToBeProcessed.size() > 0 ){
			try {
				semaphore.acquire();
			} catch (InterruptedException e) {
				e.printStackTrace(System.out);
			}
			UpdateProcessorThread upThread = new UpdateProcessorThread(props, updateStats, updatesToBeProcessed, semaphore, putSemaphore);
			uThreads.add(upThread);
			semaphore.release();
			upThread.start();
		}	
		//wait for all other threads to end
		for(UpdateProcessorThread t: uThreads){
			try {
				t.join();
			} catch (InterruptedException e) {
				e.printStackTrace(System.out);
			}
		}
		return numTotalUpdates;
	}


	public static TotalValidationThreadResults readReadFiles(Properties props, ConcurrentHashMap<String, resourceUpdateStat> updateStats, String dir,  HashMap<Integer, Integer>[] seqTracker, HashMap<String, Integer> initCnt, HashMap<Integer, Integer>[] staleSeqTracker){
		FileInputStream fstream = null;
		//discard the reads that dont have updates on their resources or those that happened before first update on their resources, or those happened after last update
		//read all read files
		int readToValidate=Integer.parseInt(props.getProperty(VALIDATION_BLOCK_PROPERTY, VALIDATION_BLOCK_PROPERTY_DEFAULT));
		int threadsToValidate = Integer.parseInt(props.getProperty(VALIDATION_THREADS_PROPERTY, VALIDATION_THREADS_PROPERTY_DEFAULT));
		int threadCount = Integer.parseInt(props.getProperty(Client.THREAD_CNT_PROPERTY, Client.THREAD_CNT_PROPERTY_DEFAULT));
		int machineid =Integer.parseInt(props.getProperty(Client.MACHINE_ID_PROPERTY, Client.MACHINE_ID_PROPERTY_DEFAULT));
		logObject[] toBeProcessed = new logObject[readToValidate];
		Vector<ValidationThread> vThreads = new Vector<ValidationThread>();
		Semaphore semaphore = new Semaphore(threadsToValidate);
		TotalValidationThreadResults finalResults = new TotalValidationThreadResults();
		Semaphore staleSeqSemaphore = new Semaphore(1);
		Semaphore seenSeqSemaphore = new Semaphore(1);

		ValidationStatusThread vsThread = new ValidationStatusThread(finalResults);
		vsThread.start();
		System.out.println("\t-- Created the validation status thread");
		int toBeProcessedArraySz = 0;
		try{
			for(int i=0; i<threadCount; i++){
				fstream = new FileInputStream(dir+"//read"+machineid+"-"+i + ".txt");
				DataInputStream in = new DataInputStream(fstream);
				BufferedReader br = new BufferedReader(new InputStreamReader(in));
				String line;
				// Read File Line By Line
				String[] tokens;
				
				while ((line = br.readLine()) != null) {
					tokens = line.split(",");
					logObject record = new logObject(tokens[0], tokens[1], tokens[2],tokens[3], tokens[4], tokens[5], tokens[6], tokens[7], "");
					toBeProcessed[toBeProcessedArraySz] = record;
					toBeProcessedArraySz++;
					if(readToValidate == toBeProcessedArraySz){
						
						try {
							semaphore.acquire();
						} catch (InterruptedException e) {
							e.printStackTrace(System.out);
						}
						//create thread to process the read records
						ValidationThread newVThread = new ValidationThread(props,toBeProcessed, updateStats, initCnt, semaphore, finalResults, staleSeqSemaphore, staleSeqTracker, seqTracker, seenSeqSemaphore, toBeProcessedArraySz);
						vThreads.add(newVThread);
						toBeProcessedArraySz = 0;
						toBeProcessed = new logObject[readToValidate];
						semaphore.release();
						newVThread.start();
					}						
				}
			}
			
		}catch(Exception e){
			e.printStackTrace(System.out);
		}


		//if any unproccessed reads process (create one thread for it)
		if(toBeProcessedArraySz > 0){
			try {
				semaphore.acquire();
			} catch (InterruptedException e) {
				e.printStackTrace(System.out);
			}
			ValidationThread newVThread = new ValidationThread(props,toBeProcessed, updateStats, initCnt, semaphore, finalResults, staleSeqSemaphore, staleSeqTracker, seqTracker, seenSeqSemaphore, toBeProcessedArraySz); //as we changed to array the exact number of items should be sent as well
			vThreads.add(newVThread);
			semaphore.release();
			newVThread.start();	
		}
		for(ValidationThread t: vThreads){
			try {
				t.join();
			} catch (InterruptedException e) {
				e.printStackTrace(System.out);
			}
		}
		//stop the validation status thread
		try {
			vsThread.setTimeToStop();
			vsThread.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		return finalResults;
	}



	public static void dumpFilesAndValidate(Properties props, HashMap<Integer, Integer>[] seqTracker , HashMap<Integer, Integer>[] staleSeqTracker, HashMap<String, Integer> stalenessStats, PrintWriter outpS, String dir){ 

		// open files for all threads one by one and read the records into the memory
		ConcurrentHashMap<String, resourceUpdateStat> updateStats = new ConcurrentHashMap<String, resourceUpdateStat>();
		HashMap<String, Integer> initCnt = CoreWorkload.initStats;		
		int numUpdates = 0;
		String ratingMode = props.getProperty(Client.RATING_MODE_PROPERTY, Client.RATING_MODE_PROPERTY_DEFAULT);
		String approach = props.getProperty(VALIDATION_APPROACH_PROPERTY,
				VALIDATION_APPROACH_PROPERTY_DEFAULT);


		if(ratingMode.equals("true")){
			outpS.write("StartingValidation ");
			outpS.flush();
		}
		long validationStart = System.currentTimeMillis();
		//read all the updates and update UpdateStats
		System.out.println("\t-- Starting to read update files...");
		long fileReadStart = System.currentTimeMillis();
		numUpdates = readUpdateFiles(props, updateStats, dir);
		if(ratingMode.equals("true")){
			outpS.write("UpdatesInDB ");
			outpS.flush();
		}
		System.out.println("\t-- Done reading update files..");
		System.out.println("\t-- UpdateProcessingDuration(ms):"+(System.currentTimeMillis()-fileReadStart));
		if(approach.equalsIgnoreCase("RDBMS")){
			buildValidationIndexes(props);
		}
		//create validation status thread
		System.out.println("\t-- Starting to read the read files...");
		fileReadStart = System.currentTimeMillis();
		TotalValidationThreadResults finalRes = readReadFiles(props, updateStats, dir, seqTracker, initCnt, staleSeqTracker);
		System.out.println("\t-- Done reading read files...");
		System.out.println("\t-- ReadValidationDuration(ms):"+(System.currentTimeMillis()-fileReadStart));
		if(ratingMode.equals("true")){
			outpS.write("DoneReadCycles ");
			outpS.flush();
			outpS.write("DOneReadValidation ");
			outpS.flush();
		}

		//populate statistics
		stalenessStats.put("NumReadOps",(finalRes.getNumReadOpsProcessed()+finalRes.getPruned()));
		stalenessStats.put("NumProcessed",finalRes.getNumReadOpsProcessed());
		stalenessStats.put("NumWriteOps",numUpdates);
		stalenessStats.put("NumStaleOps",finalRes.getNumStaleReadsreturned());
		stalenessStats.put("NumPruned",finalRes.getPruned()); 
		System.out.println("\t TotalReadOps = " + (finalRes.getNumReadOpsProcessed()+finalRes.getPruned()) + " ,staleReadOps="
				+ finalRes.getNumStaleReadsreturned() + " ,staleness Perc (gran:user)="
				+ (((double) (finalRes.getNumStaleReadsreturned()) / (finalRes.getNumReadOpsProcessed()+finalRes.getPruned()))));

		int totalSeq = 0;
		for(int j=0; j<Integer.parseInt(props.getProperty("threadcount","1")); j++){
			if(seqTracker[j] != null)
				totalSeq += seqTracker[j].size();
		}
		
		int totalStaleSeq = 0;
		for(int j=0; j<Integer.parseInt(props.getProperty("threadcount","1")); j++){
			if(staleSeqTracker[j] != null)
				totalStaleSeq += staleSeqTracker[j].size();
		}
		
		System.out.println("\t TotalSeqRead = " + totalSeq + " ,staleSeqRead="
				+ totalStaleSeq + " ,staleness Perc (gran:user)="
				+ (((double) (totalStaleSeq)) / totalSeq));
		stalenessStats.put("NumReadSessions",totalSeq);
		stalenessStats.put("NumStaleSessions",totalStaleSeq);
		stalenessStats.put("ValidationTime", (int)(System.currentTimeMillis()-validationStart));
		if(ratingMode.equals("true")){
			outpS.write("PopulateStats ");
			outpS.flush();
		}

	}


	public static void dropSequence(Statement st, String seqName) {
		try {
			st.executeUpdate("drop sequence " + seqName);
		} catch (SQLException e) {
		}
	}

	public static void dropIndex(Statement st, String idxName) {
		try {
			st.executeUpdate("drop index " + idxName);
		} catch (SQLException e) {
		}
	}

	public static void dropTable(Statement st, String tableName) {
		try {
			st.executeUpdate("drop table " + tableName);
		} catch (SQLException e) {
		}
	}


	public static void createValidationSchema(Properties props) {
		int machineid = Integer.parseInt(props.getProperty(Client.MACHINE_ID_PROPERTY,Client.MACHINE_ID_PROPERTY_DEFAULT));
		Connection conn = null;
		Statement stmt = null;
		String url = props.getProperty(VALIDATION_DBURL_PROPERTY,
				VALIDATION_DBURL_PROPERTY_DEFAULT);
		String user = props.getProperty(VALIDATION_DBUSER_PROPERTY, VALIDATION_DBUSER_PROPERTY_DEFAULT);
		String passwd = props.getProperty(VALIDATION_DBPWD_PROPERTY, VALIDATION_DBPWD_PROPERTY_DEFAULT);
		String driver = props.getProperty(VALIDATION_DBDRIVER_PROPERTY,
				VALIDATION_DBDRIVER_PROPERTY_DEFAULT);

		try {
			Class.forName(driver);
			conn = DriverManager.getConnection(url, user, passwd);
		} catch (Exception e) {
			e.printStackTrace(System.out);
		}

		String tenant = props.getProperty(DB_TENANT_PROPERTY, DB_TENANT_PROPERTY_DEFAULT);
		int threadCount = Integer.parseInt(props.getProperty(Client.THREAD_CNT_PROPERTY, Client.THREAD_CNT_PROPERTY_DEFAULT));

		try {
			stmt = conn.createStatement();
			int count = 1;
			if(tenant.equalsIgnoreCase("single")) //create one read1 and one write1 table 
				count=1;
			else
				count = threadCount; //create a read and a write table per thread

			for(int i=1; i<=count; i++){
				dropSequence(stmt, "UPDATECNT"+machineid+"c"+i);
				dropTable(stmt, "TUPDATE"+machineid+"c"+i);
				stmt.executeUpdate("CREATE SEQUENCE  UPDATECNT"+machineid+"c"+i+"  MINVALUE 1 MAXVALUE 9999999999999999999999999999 INCREMENT BY 1 START WITH 1 CACHE 20 NOORDER  NOCYCLE");
				stmt.executeUpdate("CREATE TABLE TUPDATE"+machineid+"c"+i
						+ "(	OPTYPE VARCHAR(20), UPDATEID NUMBER,SEQID NUMBER,"
						+ "THREADID NUMBER,"
						+ "RID NUMBER, STARTTIME VARCHAR2(20),"
						+ "ENDTIME VARCHAR2(20), NUMOFUPDATE NUMBER, UPDATETYPE VARCHAR2(20)"
						+ ") NOLOGGING");

				stmt.executeUpdate("ALTER TABLE TUPDATE"+machineid+"c"+i+" MODIFY (UPDATEID NOT NULL ENABLE)");
				stmt.executeUpdate("ALTER TABLE TUPDATE"+machineid+"c"+i+" MODIFY (THREADID NOT NULL ENABLE)");
				stmt.executeUpdate("ALTER TABLE TUPDATE"+machineid+"c"+i+" MODIFY (RID NOT NULL ENABLE)");


				stmt.executeUpdate("CREATE OR REPLACE TRIGGER UPDATEINC"+machineid+"c"+i+" before insert on tupdate"+machineid+"c"+i+" "
						+ "for each row "
						+ "WHEN (new.updateid is null) begin "
						+ "select updateCnt"+machineid+"c"+i+".nextval into :new.updateid from dual;"
						+ "end;");
				stmt.executeUpdate("ALTER TRIGGER UPDATEINC"+machineid+"c"+i+" ENABLE");
			}

		} catch (SQLException e) {
			e.printStackTrace(System.out);
		} finally {
			if (stmt != null){
				try {
					stmt.close();
				} catch (SQLException e) {
					e.printStackTrace(System.out);
				}
			}

			if (conn != null){
				try {
					conn.close();
				} catch (SQLException e) {
					e.printStackTrace(System.out);
				}
			}

		}
	}


	public static void main(String[] args){
		HashMap<String, Integer> stalenessStats = new HashMap<String, Integer>();
		//String dir = "C://BG//Tests//validation";
		//String dir = "C:/BG/files/6/";
		//String dir = "C:/BG/";
		String dir = "C:/BG/";
		Properties props = new Properties();
		props.setProperty("threadcount","10");
		props.setProperty("machineid","0");	
		HashMap<Integer,Integer>[] seqTracker = new HashMap[Integer.parseInt(props.getProperty("threadcount"))];
		HashMap<Integer,Integer>[] staleSeqTracker = new HashMap[Integer.parseInt(props.getProperty("threadcount"))];
		//props.setProperty("validationapproach","RDBMS"); //can be interval
		props.setProperty("validationapproach","INTERVAL"); //can be interval
		props.setProperty(VALIDATION_BLOCK_PROPERTY, "100");
		props.setProperty(VALIDATION_THREADS_PROPERTY, "5");
		props.setProperty(VALIDATION_DBURL_PROPERTY, "jdbc:oracle:thin:benchmark/111111@//10.0.0.122:1521/ORCL");
		if(props.getProperty(VALIDATION_APPROACH_PROPERTY, VALIDATION_APPROACH_PROPERTY_DEFAULT).equals("RDBMS"))
			createValidationSchema(props);
		dumpFilesAndValidate(props, seqTracker , staleSeqTracker, stalenessStats, null, dir);

	}

}






