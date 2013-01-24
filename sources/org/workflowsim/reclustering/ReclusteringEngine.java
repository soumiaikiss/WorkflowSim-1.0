/**
 *  Copyright 2007-2008 University Of Southern California
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.workflowsim.reclustering;

import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.failure.FailureMonitor;
import org.workflowsim.failure.FailureRecord;
import org.workflowsim.utils.Parameters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;

/**
 *
 * @author Weiwei Chen
 */
public class ReclusteringEngine {
    
    private static Job createJob(int id, Job job, long length, List taskList){
        try{
            Job newJob = new Job(id, length/*, job.getCloudletFileSize(), job.getCloudletOutputSize()*/);
            newJob.setUserId(job.getUserId());
            newJob.setVmId(-1);
            newJob.setCloudletStatus(Cloudlet.CREATED);
            newJob.setChildList(job.getChildList());
            newJob.setParentList(job.getParentList());
            newJob.setTaskList(taskList);
            newJob.setDepth(job.getDepth());
            for(Iterator it = job.getChildList().iterator();it.hasNext();){
                Job cJob = (Job)it.next();
                cJob.addParent(newJob);
            }

            return newJob;   
        }
        catch(Exception e){
            e.printStackTrace();
        }
        return null;
                
    }
    public static List<Job> process(Job job, int id){
        List jobList = new ArrayList();

        try{
            
            switch(Parameters.getFTCMethod())
            {
                    //case Parameters.FTCLUSTERING_NOOP:
                case FTCLUSTERING_NOOP:

                        jobList.add(createJob(id, job, job.getCloudletLength(), job.getTaskList()));
                        //job submttted doesn't have to be considered
                        break;
                    case FTCLUSTERING_DC:
                        jobList = DCReclustering(jobList, job, id, job.getTaskList());
                        break;
                    case FTCLUSTERING_SR:
                        jobList = SRReclustering(jobList, job, id);
                        
                        break;
                    case FTCLUSTERING_DR:
                        jobList = DRReclustering(jobList, job, id, job.getTaskList());
                        break;
                    case FTCLUSTERING_BLOCK:
                        jobList = BlockReclustering(jobList, job, id);
                        break;
                    case FTCLUSTERING_BINARY:
                        jobList = BinaryReclustering(jobList, job, id);
                    default:
                        break;
            }
        }catch (Exception e){
            e.printStackTrace();
        }
        return jobList;
    }
    
   private static Map getDepthMap(List list){
       Map map = new HashMap<Integer, List>();
       
       for(Iterator it = list.iterator(); it.hasNext();){
           Task task = (Task)it.next();
           int depth = task.getDepth();
           if(!map.containsKey(depth)){
               map.put(depth, new ArrayList<Task>());
           }
           List dl = (List)map.get(depth);
           if(!dl.contains(task)){
               dl.add(task);
           }
       }
       
       return map;
   }
   //DR + level aware
   private static int getMin(Map map){
       if(map!=null && !map.isEmpty()){
           int min = 10000;//bug here
           for(Iterator it = map.keySet().iterator();it.hasNext();){
               int value = (Integer)it.next();
               if(value < min){
                   min = value;
               }
           }
           return min;
       }
       return -1;
   }
   
   private static boolean checkFailed(List list){
       boolean all = false;
       for(Iterator it = list.iterator(); it.hasNext();){
           Task task = (Task)it.next();
           if(task.getCloudletStatus() == Cloudlet.FAILED){
               all = true;
               break;
           }
       }
       return all;
   }
   
   
   private static List BinaryReclustering(List jobList, Job job, int id){
       Log.printLine("Job Id for reclustering" + job.getCloudletId());
       Map map = getDepthMap(job.getTaskList());
       if(map.size() == 1 ){
           
           jobList = DCReclustering(jobList, job, id, job.getTaskList());
           
           return jobList;
       }
       int min = getMin(map);
       int max = min + map.size()  - 1;
       int mid = (min + max ) / 2;
       List listUp = new ArrayList<Task>();
       List listDown = new ArrayList<Task>();
       for(int i = min; i < min + map.size() ; i++){
           List list = (List)map.get(i);
           if(i<=mid){
               listUp.addAll(list);
           }else{
               listDown.addAll(list);
           }
       }
       List newUpList = DCReclustering(new ArrayList(), job, id, listUp);
       id += newUpList.size();
       jobList.addAll(newUpList);
       jobList.addAll(DCReclustering(new ArrayList(), job, id, listDown));
       return jobList;
       
   }
   
   private static List BlockReclustering(List jobList, Job job, int id){
       Map map = getDepthMap(job.getTaskList());
       if(map.size()==1){
           jobList = DRReclustering(jobList, job, id, job.getTaskList());
           return jobList;
       }
       //do that to every list
       int min = getMin(map);
       //List freeList = new ArrayList<Task>();
       for(int i = min; i < min + map.size(); i++){
           
           List list = (List)map.get(i);
           
           if(checkFailed(list)){
               //should be separate
                List newList = DRReclustering(new ArrayList(), job, id, list);
                id = newList.size() + id;//?
                jobList.addAll(newList);
           }else{
               //do nothing
           }
       }
       
       
       return jobList;
   }
   //DC + BS
   
   
   private static List DCReclustering(List jobList, Job job, int id, List allTaskList){
      
        Task firstTask = (Task)allTaskList.get(0);
        List tmpList = new ArrayList();
        tmpList.add(job);
        double delay = Parameters.getOverheadParams().getQueueDelay(job)
                + Parameters.getOverheadParams().getWEDDelay(tmpList)
                + Parameters.getOverheadParams().getPostDelay(job)
                ;
        //Definition of FailureRecord(long length, int tasks, int depth, int all, int vm, int job, int workflow)
        double taskLength = (double)firstTask.getCloudletLength()/1000 + Parameters.getOverheadParams().getClustDelay(job)/getDividend(job.getDepth()) ;
        FailureRecord record = new FailureRecord(taskLength, 0, job.getDepth(), allTaskList.size(), 0, 0, job.getUserId());
        record.delayLength  = delay;
        Log.printLine("record t:" + record.length +" d: " + record.delayLength  );
        int suggestedK = FailureMonitor.getClusteringFactor( record);

        //if(suggestedK > 100) suggestedK = 100;
        double a = FailureMonitor.analyze(0, job.getDepth());
        Log.printLine("Depth: "+ job.getDepth() + " K " + suggestedK + " a: " + a);

        if(suggestedK == 0){
            //not really k=0, just too big
            jobList.add(createJob(id, job, job.getCloudletLength(), allTaskList));
        }else{

            int actualK = 0;
            List taskList = new ArrayList();
            long length = 0;
            Job newJob = createJob(id, job, 0, null);
            for(int i = 0; i < allTaskList.size();i++){
                Task task = (Task)allTaskList.get(i);
                if(actualK < suggestedK){
                    actualK ++ ;
                    taskList.add(task);
                    length += task.getCloudletLength();
                }else {
                    newJob.setTaskList(taskList);
                    taskList = new ArrayList();
                    newJob.setCloudletLength(length);
                    length = 0;
                    jobList.add(newJob);
                    id++;
                    newJob=createJob(id, job, 0, null);
                    actualK = 0;//really a f*k bug
                }
            }


            if(!taskList.isEmpty()){
                newJob.setTaskList(taskList);
                newJob.setCloudletLength(length);
                jobList.add(newJob);
            }

        }
        return jobList;
                        
   }
    
   private static List SRReclustering(List jobList, Job job, int id){
        int size = job.getTaskList().size();
         List newTaskList = new ArrayList();
         long length = 0;
         for(int i= 0; i < size; i++ ){
             Task task = (Task)job.getTaskList().get(i);
             if(task.getCloudletStatus() == Cloudlet.FAILED){
                 newTaskList.add(task);
                 length += task.getCloudletLength();
             }
             else{
                 
             }
         }
         //may have bug
         Log.printLine("Doesn't consider the data transfer problem");
         jobList.add(createJob(id, job, length, newTaskList));
         //Log.printLine(job.getCloudletId() +  " with" + job.getCloudletLength() + " creates  new job " + id + " with " + length);
         return jobList;
   }
   
   private static int getDividend(int depth){
       int dividend = 1;
       switch (depth){
           case 1:
               dividend = 78;
               break;
           case 2:
               dividend = 229;
               break;
           case 5:
               dividend = 64;
               break;
           default:
               Log.printLine("Eroor");
               break;
       }
       return dividend;
   }
   
   private static List DRReclustering(List jobList, Job job, int id, List allTaskList){
        Task firstTask = (Task)allTaskList.get(0);
        List tmpList = new ArrayList();
        tmpList.add(job);
        double delay = Parameters.getOverheadParams().getQueueDelay(job)
                + Parameters.getOverheadParams().getWEDDelay(tmpList)
                + Parameters.getOverheadParams().getPostDelay(job)
                /*+ Parameters.getOverheadParams().getClustDelay(job)*/;
        //Definition of FailureRecord(long length, int tasks, int depth, int all, int vm, int job, int workflow)
        double taskLength = (double)firstTask.getCloudletLength()/1000 + Parameters.getOverheadParams().getClustDelay(job)/getDividend(job.getDepth());
        if(taskLength > 20){
            Log.printLine();
        }
        FailureRecord record = new FailureRecord(taskLength, 0, job.getDepth(), allTaskList.size(), 0, 0, job.getUserId());
        record.delayLength  = delay;
        Log.printLine("record t:" + record.length +" d: " + record.delayLength  );
        int suggestedK = FailureMonitor.getClusteringFactor( record);
        Log.printLine("K: " + suggestedK + " a " + FailureMonitor.analyze(0, job.getDepth())) ;
        
        //if(suggestedK > 50) suggestedK = 50;
        if(suggestedK == 0){
            //not really k=0, just too big
            jobList.add(createJob(id, job, job.getCloudletLength(), allTaskList));
        }else{

            int actualK = 0;
            List taskList = new ArrayList();
            long length = 0;
            Job newJob = createJob(id, job, 0, null);
            for(int i = 0; i < allTaskList.size();i++){
                Task task = (Task)allTaskList.get(i);
                if(task.getCloudletStatus() == Cloudlet.FAILED){//This is the difference
                    if(actualK < suggestedK){
                        actualK ++ ;
                        taskList.add(task);
                        
                        length += task.getCloudletLength();
                    }else {
                        newJob.setTaskList(taskList);
                        taskList = new ArrayList();
                        newJob.setCloudletLength(length);
                        length = 0;
                        jobList.add(newJob);
                        id++;
                        newJob=createJob(id, job, 0, null);
                        actualK=0;
                    }
                }
            }


            if(!taskList.isEmpty()){
                newJob.setTaskList(taskList);
                newJob.setCloudletLength(length);
                jobList.add(newJob);
            }

        }
        return jobList;
   }
}
