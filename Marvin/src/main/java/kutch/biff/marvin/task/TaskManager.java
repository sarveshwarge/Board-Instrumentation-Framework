/*
 * ##############################################################################
 * #  Copyright (c) 2016 Intel Corporation
 * # 
 * # Licensed under the Apache License, Version 2.0 (the "License");
 * #  you may not use this file except in compliance with the License.
 * #  You may obtain a copy of the License at
 * # 
 * #      http://www.apache.org/licenses/LICENSE-2.0
 * # 
 * #  Unless required by applicable law or agreed to in writing, software
 * #  distributed under the License is distributed on an "AS IS" BASIS,
 * #  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * #  See the License for the specific language governing permissions and
 * #  limitations under the License.
 * ##############################################################################
 * #    File Abstract: 
 * #
 * #
 * ##############################################################################
 */
package kutch.biff.marvin.task;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import javafx.application.Platform;
import kutch.biff.marvin.datamanager.DataManager;
import kutch.biff.marvin.logger.MarvinLogger;
import kutch.biff.marvin.network.Client;
import kutch.biff.marvin.utility.FrameworkNode;
import kutch.biff.marvin.utility.Utility;

/**
 *
 * @author Patrick Kutch
 */
public class TaskManager
{

    private final static Logger LOGGER = Logger.getLogger(MarvinLogger.class.getName());
    private static TaskManager _TaskManager = null;
    private static ArrayList<String> _OnStartupList = null;
    private static ArrayList<String> _OnConnectedList = null;

    private ConcurrentHashMap<String, TaskList> _TaskMap;
    private ConcurrentHashMap<String, Client> _ClientMap;
    private DataManager _DataMgr;
    private final ArrayList<String> _DeferredTasks;
    private final ArrayList<PostponedTask> _PostponedTasks;
    private final ArrayList<PostponedTask> _PostponedTasksNew;
    private final ArrayList<ITask> _PostponedTaskObjectThatMustBeRunInGuiThreadList;
    private long _TasksPerformed;
    private int _LoopsWithManyTasks;
    private static boolean _WarningAboutManyTasksSent = false;

    public TaskManager()
    {
        _TaskMap = new ConcurrentHashMap<>();
        _DataMgr = null;
        _ClientMap = null;
        _DeferredTasks = new ArrayList<>();
        _PostponedTasks = new ArrayList<>();
        _PostponedTasksNew = new ArrayList<>();
        _PostponedTaskObjectThatMustBeRunInGuiThreadList = new ArrayList<>();
        _TasksPerformed = 0;
        _LoopsWithManyTasks  = 0;
    }

    // this is where a task comes in on a woker thread (like remote marvin) 
    public void AddDeferredTask(String newTask)
    {
        synchronized (_DeferredTasks)
        {
            _DeferredTasks.add(newTask);
        }
    }

    public void AddDeferredTaskObject(ITask objTask)
    {
        synchronized (_PostponedTaskObjectThatMustBeRunInGuiThreadList)
        {
            _PostponedTaskObjectThatMustBeRunInGuiThreadList.add(objTask);
        }
    }

    public long GetPerformedCount()
    {
        return _TasksPerformed;
    }

    public void PerformDeferredTasks()
    {
        boolean fDone = false;
        int size = 0;
        ArrayList<String> localDeferredTasksRoRun = new ArrayList<>();
        ArrayList<ITask> localPostponedTaskstoRun = new ArrayList<>();

        synchronized (_DeferredTasks) // make a quick copy to reduce time in synchronized block
        {
            size = _DeferredTasks.size();

            localDeferredTasksRoRun.addAll(_DeferredTasks);
            _DeferredTasks.clear();
        }

        if ( size > 256 && !_WarningAboutManyTasksSent)
        {
            if (_LoopsWithManyTasks++ > 5)
            {
                LOGGER.warning(" There are " + size + " Tasks queued up to be performed. That is a lot - you MAY have a circular logic bomb in <Conditionals>.  This is the last warning for this potential problem.");
                _WarningAboutManyTasksSent = true;
            }
            else
            {
                LOGGER.warning(" There are " + size + " Tasks queued up to be performed. That is a lot - you MAY have a circular logic bomb in <Conditionals>.");
            }
        }

        String Task;
        while (!fDone)
        {
            Task = null;
            if (localDeferredTasksRoRun.isEmpty())
            {
                fDone = true;
            }
            else
            {
                Task = localDeferredTasksRoRun.remove(0);
            }

            if (null != Task)
            {
                // deferred tasks MUST be in GUI thread, so use runLater
                final String strTask = Task;
                Platform.runLater(new Runnable()
                {
                    @Override
                    public void run()
                    { // go run this in a GUI thread
                        // 
                        PerformTask(strTask);
                    }
                });
            }
        }

        fDone = false;
        ITask objTask;
        // Now go and process all of the postponed tasks that need to be done in gui thread

        synchronized (_PostponedTaskObjectThatMustBeRunInGuiThreadList)
        {
             size = _PostponedTaskObjectThatMustBeRunInGuiThreadList.size();
            localPostponedTaskstoRun.addAll(_PostponedTaskObjectThatMustBeRunInGuiThreadList);
            _PostponedTaskObjectThatMustBeRunInGuiThreadList.clear();
        }
        while (!fDone)
        {
            objTask = null;
            if (localPostponedTaskstoRun.isEmpty())
            {
                fDone = true;
            }
            else
            {
                objTask = localPostponedTaskstoRun.remove(0);
            }
            if (null != objTask)
            {
               // final ITask tmp_objTask = objTask;
                //objTask.PerformTask();
                //PerformTask(objTask);
                PerformThreadedTask(objTask);

            }
        }
        PerformPostponedTasks();
    }

    private void PerformThreadedTask(ITask objTask)
    {
        Platform.runLater(new Runnable()
        {
            @Override
            public void run()
            {
                objTask.PerformTask();
            }
        });
    }

    public void AddOnStartupTask(String TaskID, BaseTask objTaskToPerform)
    {
        if (false == _TaskMap.containsKey(TaskID.toUpperCase()))
        {
            TaskList objTask = new TaskList();
            objTask.AddTaskItem(objTaskToPerform);
            _TaskMap.put(TaskID.toUpperCase(), objTask);
            if (null == _OnStartupList)
            {
                _OnStartupList = new ArrayList<>();
            }
            _OnStartupList.add(TaskID);
        }
    }

    /**
     * Go through and perform the postponed tasks
     */
    public void PerformPostponedTasks()
    {
        ArrayList<PostponedTask> toRunList = null;
        synchronized (_PostponedTasks)
        {
            for (PostponedTask objTask : _PostponedTasks)
            {
                if (objTask.ReadyToPerform())
                {
                    if (null == toRunList)
                    {
                        toRunList = new ArrayList<>();
                    }
                    toRunList.add(objTask); // Add it to a temp list, don't want to run here in sync'd loop
                }
            }
            if (null != toRunList)
            {
                for (PostponedTask objTask : toRunList)
                {
                    _PostponedTasks.remove(objTask);
                }
            }
        }

        if (null != toRunList) // nuken
        {
            for (PostponedTask objTask : toRunList)
            {
                objTask.Perform();
            }

            toRunList.clear(); // probably redundant,
        }

        synchronized (_PostponedTasksNew) // New postponed tasks came in while during processing of postponed tasks thread
        {
            synchronized (_PostponedTasks)
            {
                _PostponedTasks.addAll(_PostponedTasksNew); // add to postponed list for processing next time
            }
            _PostponedTasksNew.clear();
        }
    }

    public void AddPostponedTask(ITask objTask, long Period)
    {
        synchronized (_PostponedTasks)
        {
            _PostponedTasks.add(new PostponedTask(objTask, Period));
        }
    }

    // Sometimes there are postponed tasks to be added while processing postponed
    // tasks.  Since you can't go and add stuff to the _PostponedTask list while it
    // is being processed, add to the temp one.
    public void AddPostponedTaskThreaded(ITask objTask, long Period)
    {
        synchronized (_PostponedTasksNew)
        {
            _PostponedTasksNew.add(new PostponedTask(objTask, Period));
        }
    }

    public static TaskManager getTaskManager()
    {
        if (null == _TaskManager)
        {
            _TaskManager = new TaskManager();
        }
        return _TaskManager;
    }

    public DataManager getDataMgr()
    {
        return _DataMgr;
    }

    public int getNumberOfTasks()
    {
        if (null == _TaskMap)
        {
            return 0;
        }
        return _TaskMap.size();
    }

    public void setDataMgr(DataManager _DataMgr)
    {
        this._DataMgr = _DataMgr;
    }

    private int RunThroughList(ArrayList<String> list)
    {
        int RetVal = 0;
        if (null != list)
        {
            for (String taskID : list)
            {
                PerformTask(taskID);
            }
            RetVal = list.size();
        }
        return RetVal;
    }

    /**
     *
     * @return
     */
    public int PerformOnConnectedTasks()
    {
        WatchdogTask.OnInitialOscarConnection(); // Send the 'Refresh' message NOW rather than after the watchdog interval
        int RetVal = RunThroughList(_OnConnectedList);
        if (RetVal > 0)
        {
            LOGGER.info("Performed [" + Integer.toString(RetVal) + "] tasks after connection establshed");
        }
        return RetVal;
    }

    public int PerformOnStartupTasks()
    {
        int RetVal = RunThroughList(_OnStartupList);
        LOGGER.info("Performed [" + Integer.toString(RetVal) + "] tasks after startup");

        return RetVal;
    }

    private long ReadTaskPostpone(String strPostpone)
    {
        long Postpone = 0;
        try
        {
            Postpone = Integer.parseInt(strPostpone);
            if (Postpone < 0)
            {
                LOGGER.severe("Invalid Postpone value: " + strPostpone + ".  Setting to 0");
                Postpone = 0;
            }

        }
        catch (NumberFormatException ex)
        {
            if (strPostpone.contains(":")) // could be a random range!
            {
                String[] parts = strPostpone.split(":");

                if (2 == parts.length)
                {
                    String strRange1 = parts[0];
                    String strRange2 = parts[1];
                    int bound1 = (int) ReadTaskPostpone(strRange1);
                    int bound2 = (int) ReadTaskPostpone(strRange2);
                    if (bound1 > bound2)
                    {
                        int tLong = bound1;
                        bound1 = bound2;
                        bound2 = tLong;
                    }
                    Random rand = new Random();

                    Postpone = (long) (bound1 + rand.nextInt((bound2 - bound1) + 1));

                }
            }
            else
            {
                LOGGER.severe("Invalid Postpone value: " + strPostpone + ".  Setting to 0");
                Postpone = 0;
            }

        }
        return Postpone;
    }

    public boolean CreateTask(String ID, FrameworkNode masterNode)
    {
        boolean retVal = false;
        boolean OnStartup = false;
        boolean OnConnected = false;
        TaskList objTask = null;

        if (true == masterNode.hasAttribute("Stepped") && masterNode.getBooleanAttribute("Stepped"))
        {
            SteppedTaskList objSteppedTask = new SteppedTaskList();

            if (true == masterNode.hasAttribute("LoopTasks"))
            {
                objSteppedTask.setLooped(masterNode.getBooleanAttribute("PerformOnStartup"));
            }
            objTask = objSteppedTask;
        }
        else
        {
            objTask = new TaskList();
        }

        if (true == masterNode.hasAttribute("PerformOnStartup"))
        {
            OnStartup = masterNode.getBooleanAttribute("PerformOnStartup");
        }

        if (true == masterNode.hasAttribute("PerformOnConnect"))
        {
            OnConnected = masterNode.getBooleanAttribute("PerformOnConnect");
        }

        for (FrameworkNode node : masterNode.getChildNodes())
        {
            long Postpone = 0;
            if (0 == node.getNodeName().compareToIgnoreCase("#text") || 0 == node.getNodeName().compareToIgnoreCase("#comment"))
            {
                continue;
            }

            if (node.getNodeName().equalsIgnoreCase("TaskItem"))
            {
                if (false == node.hasAttribute("Type"))
                {
                    LOGGER.severe("Task with ID: " + ID + " contains a TaskItem with no Type");
                    continue;
                }
                if (node.hasAttribute("Postpone"))
                {
                    Postpone = ReadTaskPostpone(node.getAttribute("Postpone"));
                }

                String taskType = node.getAttribute("Type");
                BaseTask objTaskItem = null;
                if (0 == taskType.compareToIgnoreCase("Oscar"))
                {
                    objTaskItem = BuildOscarTaskItem(ID, node);
                }
                else if (0 == taskType.compareToIgnoreCase("Minion"))
                {
                    objTaskItem = BuildMinionTaskItem(ID, node);
                }
                else if (0 == taskType.compareToIgnoreCase("Marvin"))
                {
                    objTaskItem = BuildMarvinTaskItem(ID, node);
                }
                else if (0 == taskType.compareToIgnoreCase("Mathematic"))
                {
                    objTaskItem = BuildMathematicTaskTaskItem(ID, node);
                }

                else if (0 == taskType.compareToIgnoreCase("DataPulse"))
                {
                    objTaskItem = BuildPulseTaskItem(ID, node);
                }
                else if (0 == taskType.compareToIgnoreCase("OtherTask"))
                {
                    objTaskItem = BuildChainedTaskItem(ID, node);
                }
                else if (0 == taskType.compareToIgnoreCase("MarvinAdmin"))
                {
                    objTaskItem = BuildMarvinAdminTaskItem(ID, node);
                }
                else if (0 == taskType.compareToIgnoreCase("RemoteMarvinTask"))
                {
                    objTaskItem = BuildRemoteMarvinTaskItem(ID, node);
                }
                else if (taskType.equalsIgnoreCase("RandomTask") || taskType.equalsIgnoreCase("Random"))
                {
                    objTaskItem = BuildRandomTaskItem(ID, node);
                }

                else if (taskType.equalsIgnoreCase("Desktop"))
                {
                    objTaskItem = BuildDesktopTaskItem(ID, node);
                }
                else if (taskType.equalsIgnoreCase("LaunchApplication") || taskType.equalsIgnoreCase("LaunchApp") || taskType.equalsIgnoreCase("LaunchProgram") || 
                         taskType.equalsIgnoreCase("RunProgram") || taskType.equalsIgnoreCase("RunApp"))
                {
                    objTaskItem = BuildLaunchApplicationTaskItem(ID, node);
                }
                
                else
                {
                    LOGGER.severe("Task with ID: " + ID + " contains a TaskItem of unknown Type of " + taskType + ".");
                }
                if (null != objTaskItem)
                {
                    objTaskItem.setPostponePeriod(Postpone);
                    objTask.AddTaskItem(objTaskItem);
                    retVal = true;
                }
            }
        }
        if (true == retVal)
        {
            if (false == AddNewTask(ID, objTask, OnStartup, OnConnected))
            {
                retVal = false;
            }
        }
        else
        {
            LOGGER.severe("Task with ID: " + ID + " is invalid.");
        }
        return retVal;
    }

    /**
     * helper routine to see if a Taks with the given ID already exists
     *
     * @param TaskID
     * @return true if the TaskList with the TaskID already exists, else false
     */
    public boolean TaskExists(String TaskID)
    {
        if (null != _TaskMap)
        {
            return _TaskMap.containsKey(TaskID.toUpperCase());
        }
        return false;
    }

    /**
     * Puts a new task in the internal collection
     *
     * @param TaskID Unique ID
     * @param objTask the task object
     * @return true if success
     */
    private boolean AddNewTask(String TaskID, TaskList objTask, boolean onStartup, boolean onConnected)
    {
        if (null == _TaskMap)
        {
            _TaskMap = new ConcurrentHashMap<>();
        }

        if (false == _TaskMap.containsKey(TaskID.toUpperCase()))
        {
            _TaskMap.put(TaskID.toUpperCase(), objTask);
            if (true == onStartup)
            {
                if (null == _OnStartupList)
                {
                    _OnStartupList = new ArrayList<>();
                }
                _OnStartupList.add(TaskID);
            }
            if (true == onConnected)
            {
                if (null == _OnConnectedList)
                {
                    _OnConnectedList = new ArrayList<>();
                }
                _OnConnectedList.add(TaskID);
            }
            return true;
        }
        LOGGER.config("Duplicate Task with ID of " + TaskID + " found. Ignoring.");
        return true;
    }

    /**
     * Common function for tasks, reads the <Param> sections
     *
     * @param taskNode
     * @return an array of the parameters
     */
    private ArrayList<Parameter> GetParameters(FrameworkNode taskNode)
    {
        ArrayList<Parameter> Params = null;

        for (FrameworkNode node : taskNode.getChildNodes())
        {
            if (0 == node.getNodeName().compareToIgnoreCase("#text") || 0 == node.getNodeName().compareToIgnoreCase("#comment"))
            {
            }
            else if (node.getNodeName().equalsIgnoreCase("Param"))
            {
                if (null == Params)
                {
                    Params = new ArrayList<>();
                }
                if (node.getTextContent().length() > 0)
                {
                    if (node.hasAttributes())
                    {
                        LOGGER.severe("Specified a value and an attribute for <Param>.  They are mutually exclusive.");
                    }

                    Params.add(new Parameter(node.getTextContent()));
                }
                else if (node.hasAttribute("Namespace") || node.hasAttribute("id"))
                {
                    if (!node.hasAttribute("Namespace"))
                    {
                        LOGGER.severe("Specified a <Param> with intent to use Namespace & ID, but did not specify Namespace.");
                        Params.clear(); // return an empty, but non-null list to know there was a problem.
                        break;
                    }
                    if (!node.hasAttribute("id"))
                    {
                        LOGGER.severe("Specified a <Param> with intent to use Namespace & ID, but did not specify ID.");
                        Params.clear(); // return an empty, but non-null list to know there was a problem.
                        break;
                    }
                    String NS = node.getAttribute("Namespace");
                    String ID = node.getAttribute("ID");
                    DataSrcParameter param = new DataSrcParameter(NS, ID, getDataMgr());
                    Params.add(param);
                    LOGGER.info("Creating <Param> with input from Namespace=" + NS + " and ID=" + ID);
                }
                else
                {
                    Params.clear(); // return an empty, but non-null list to know there was a problem.
                    LOGGER.severe("Empty <Param> in task");
                    break;
                }
            }
        }

        return Params;
    }

    /**
     * Read the parameters from the xml file for a Minion task
     *
     * @param taskID ID of the task
     * @param taskNode XML node
     * @return A MinionTask object
     */
    private MinionTask BuildMinionTaskItem(String taskID, FrameworkNode taskNode)
    {
        /**
         * * Example Task
         * <TaskItem Type="Minion">
         * <Actor Namespace="fubar" ID="EnableRSS"/>
         * <Param>16</Param>
         */

        MinionTask objMinionTask = new MinionTask();
        objMinionTask.setParams(GetParameters(taskNode));
        if (objMinionTask.getParams() != null && objMinionTask.getParams().size() == 0)
        {
            return null;  // means had problem loading params.
        }

        for (FrameworkNode node : taskNode.getChildNodes())
        {
            if (node.getNodeName().equalsIgnoreCase("Actor"))
            {
                Utility.ValidateAttributes(new String[]
                {
                    "Namespace", "ID"
                }, node);

                if (node.hasAttribute("Namespace") && node.hasAttribute("ID"))
                {
                    objMinionTask.setID(node.getAttribute("ID"));
                    objMinionTask.setNamespace(node.getAttribute("Namespace"));
                }
                else
                {
                    LOGGER.severe("Task with ID: " + taskID + " contains an invalid Minion Task");
                    return null;
                }
            }
        }

        return objMinionTask;
    }

    /**
     * Read the parameters from the xml file for a Marvin (local) task
     *
     * @param taskID ID of the task
     * @param taskNode XML node
     * @return A MarvinTask object
     */
    private MarvinTask BuildMarvinTaskItem(String taskID, FrameworkNode taskNode)
    {
        /**
         * * Example Task
         * <TaskItem Type="Marvin">
         * <DataToInsert ID="Text" Namespace="PK Laptop" Data="First Text"/>
         * </TaskItem>
         */
        MarvinTask objMarvinTask = new MarvinTask();
        objMarvinTask.setParams(GetParameters(taskNode));
        if (objMarvinTask.getParams() != null && objMarvinTask.getParams().size() == 0)
        {
            return null;  // means had problem loading params.
        }

        for (FrameworkNode node : taskNode.getChildNodes())
        {
            if (node.getNodeName().equalsIgnoreCase("DataToInsert"))
            {
                Utility.ValidateAttributes(new String[]
                {
                    "Namespace", "ID", "Data"
                }, node);
                if (node.hasAttribute("Namespace") && node.hasAttribute("ID"))
                {
                    String strData = null;
                    if (node.hasAttribute("Data"))
                    {
                        strData = node.getAttribute("Data");
                    }
                    else
                    {
                        for (FrameworkNode dataNode : node.getChildNodes())
                        {
                            if (dataNode.getNodeName().equalsIgnoreCase("Data"))
                            {
                                strData = dataNode.getTextContent();
                                break;
                            }
                        }
                    }
                    if (strData != null)
                    {
                        objMarvinTask.AddDataset(node.getAttribute("ID"), node.getAttribute("Namespace"), strData);
                    }
                    else
                    {
                        LOGGER.severe("Task with ID: " + taskID + " contains an invalid Marvin Task - no Data defined");
                    }

                }
                else
                {
                    LOGGER.severe("Task with ID: " + taskID + " contains an invalid Marvin Task");
                    return null;
                }
            }
            else
            {
                LOGGER.severe("Task with ID: " + taskID + " contains an unknown tag: " + node.getNodeName() );
            }
        }
        
        if (!objMarvinTask.isValid())
        {
            objMarvinTask = null;
            LOGGER.severe("Task with ID: " + taskID + " contains an invalid Marvin Task");
        }

        return objMarvinTask;
    }

    private PulseTask BuildPulseTaskItem(String taskID, FrameworkNode taskNode)
    {
        /**
         * * Example Task
         * <TaskItem Type="Pulse">
         * <MarvinDataPoint ID="Text" Namespace="PK Laptop"/>
         * </TaskItem>
         */
        boolean errorLogged = false;
        PulseTask objPulseTask = new PulseTask();

        for (FrameworkNode node : taskNode.getChildNodes())
        {
            if (node.getNodeName().equalsIgnoreCase("MarvinDataPoint"))
            {
                Utility.ValidateAttributes(new String[]
                {
                    "Namespace", "ID"
                }, node);
                if (node.hasAttribute("Namespace") && node.hasAttribute("ID"))
                {
                    objPulseTask.SetNamespaceAndID(node.getAttribute("Namespace"), node.getAttribute("ID"));
                }
                else
                {
                    LOGGER.severe("Task with ID: " + taskID + " contains an invalid Pulse Task - no Namespace and ID defined in MarvinDataPoint");
                    errorLogged = true;
                }
            }
        }
        if (!objPulseTask.isValid())
        {
            objPulseTask = null;
            if (!errorLogged)
            {
                LOGGER.severe("Task with ID: " + taskID + " contains an invalid Pulse Task - no MarvinDataPoint defined");
            }
        }
        return objPulseTask;
    }

    private PulseTask BuildMathematicTaskTaskItem(String taskID, FrameworkNode taskNode)
    {
        /**
         * * Example Task
         * <TaskItem Type="Mathematic">
         * <MarvinDataPoint ID="Text" Namespace="PK Laptop"/>
         * <Operation Value=".1">Add</Operation> Subtract, Multiply
         * </TaskItem>
         */
        boolean errorLogged = false;
        MathematicTask objTask = new MathematicTask();

        for (FrameworkNode node : taskNode.getChildNodes())
        {
            if (node.getNodeName().equalsIgnoreCase("MarvinDataPoint"))
            {
                Utility.ValidateAttributes(new String[]
                {
                    "Namespace", "ID"
                }, node);
                if (node.hasAttribute("Namespace") && node.hasAttribute("ID"))
                {
                    objTask.SetNamespaceAndID(node.getAttribute("Namespace"), node.getAttribute("ID"));
                }
                else
                {
                    LOGGER.severe("Task with ID: " + taskID + " contains an invalid Mathematic Task - no Namespace and ID defined in MarvinDataPoint");
                    errorLogged = true;
                }
            }
            else if (node.getNodeName().equalsIgnoreCase("Operation"))
            {
                Utility.ValidateAttributes(new String[]
                {
                    "Value"
                }, node);
                if (node.hasAttribute("Value"))
                {
                    if (!objTask.setValue(node.getAttribute("Value")))
                    {
                        LOGGER.severe("Task with ID: " + taskID + " contains an invalid Mathematic Task Operation Value: " + node.getAttribute("Value"));
                        errorLogged = true;
                    }
                }
                String strOperationType = node.getTextContent();
                if (!objTask.SetOperation(strOperationType))
                {
                    LOGGER.severe("Task with ID: " + taskID + " contains an invalid Mathematic Task Operation : " + strOperationType);
                    errorLogged = true;
                }

            }
        }
        if (!objTask.isValid())
        {
            objTask = null;
            if (!errorLogged)
            {
                LOGGER.severe("Task with ID: " + taskID + " contains an invalid Arithmatic Task - no MarvinDataPoint defined");
            }
        }
        return objTask;
    }

    private MarvinAdminTask BuildMarvinAdminTaskItem(String taskID, FrameworkNode taskNode)
    {
        /**
         * * Example Task
         * <TaskItem Type="MarvinAdmin">
         * <Task ID="TabChange"  Data="DemoTab-Indicators"/>
         * </TaskItem>
         */
        String ID, Data;
        ID = "";
        Data = "";
        boolean TaskFound = false;
        for (FrameworkNode node : taskNode.getChildNodes())
        {
            if (node.getNodeName().equalsIgnoreCase("Task"))
            {
                TaskFound = true;
                Utility.ValidateAttributes(new String[]
                {
                    "Data", "ID"
                }, node);

                if (node.hasAttribute("ID"))
                {
                    ID = node.getAttribute("ID");
                }
                else
                {
                    LOGGER.severe("Invalid MarvinAdminTask with Task ID: " + taskID + " - no ID specified.");
                    return null;
                }
                if (node.hasAttribute("Data"))
                {
                    Data = node.getAttribute("Data");
                }
                else
                {
                    Data = "";
                }
            }
        }
        if (!TaskFound)
        {
            LOGGER.severe("Invalid MarvinAdminTask with Task ID: " + taskID + " - no Task specified.");
            return null;
        }
        MarvinAdminTask objTask = new MarvinAdminTask(taskID, ID, Data);

        return objTask;
    }

    private RemoteMarvinTask BuildRemoteMarvinTaskItem(String taskID, FrameworkNode taskNode)
    {
        /**
         * * Example Task
         * <TaskItem Type="MarvinAdmin">
         * <Task ID="TabChange"/>
         * </TaskItem>
         */
        String RemoteTaskID, RemoteMarvinID;
        RemoteTaskID = "";
        RemoteMarvinID = "";
        for (FrameworkNode node : taskNode.getChildNodes())
        {
            if (node.getNodeName().equalsIgnoreCase("Task"))
            {
                Utility.ValidateAttributes(new String[]
                {
                    "ID"
                }, node);

                if (node.hasAttribute("ID"))
                {
                    RemoteTaskID = node.getAttribute("ID");
                }
                else
                {
                    LOGGER.severe("Invalid MarvinAdminTask with Task ID: " + taskID + " - no ID specified.");
                    return null;
                }
            }
            if (node.getNodeName().equalsIgnoreCase("MarvinID"))
            {
                RemoteMarvinID = node.getTextContent();
            }
        }

        RemoteMarvinTask objTask = new RemoteMarvinTask(RemoteMarvinID, RemoteTaskID);

        return objTask;
    }

    /**
     * Read the parameters from the xml file for a Oscar task
     *
     * @param taskID ID of the task
     * @param taskNode XML node
     * @return A OscarTask object
     */
    private OscarTask BuildOscarTaskItem(String taskID, FrameworkNode taskNode)
    {
        /**
         * * Example Task
         * <TaskItem Type="Oscar">
         * <Task OscarID="Fubar">Load File</Task>
         * <Param>mysave.glk</Param>
         * </TaskItem>
         */
        OscarTask objOscarTask = new OscarTask();
        objOscarTask.setParams(GetParameters(taskNode));
        if (objOscarTask.getParams() != null && objOscarTask.getParams().size() == 0)
        {
            return null;  // means had problem loading params.
        }

        for (FrameworkNode node : taskNode.getChildNodes())
        {
            if (node.getNodeName().equalsIgnoreCase("Task"))
            {
                Utility.ValidateAttributes(new String[]
                {
                    "OscarID"
                }, node);

                if (node.hasAttribute("OscarID"))
                {
                    objOscarTask.setOscarID(node.getAttribute("OscarID"));
                    objOscarTask.setTaskID(node.getTextContent());
                }
                else
                {
                    LOGGER.severe("Task with ID: " + taskID + " contains an invalid Oscar Task");
                    return null;
                }
            }
        }
        return objOscarTask;
    }

    private ChainedTask BuildChainedTaskItem(String taskID, FrameworkNode taskNode)
    {
        if (taskNode.hasAttribute("ID"))
        {
            return new ChainedTask(taskNode.getAttribute("ID"));
        }

        return null;
    }
    
    private DesktopTask BuildDesktopTaskItem(String taskID, FrameworkNode taskNode)
    {
        /**
	<TaskList ID="TestDesktop">
		<TaskItem Type="Desktop">
			<Document Action="Open">foo.html</Document>
		</TaskItem>
	</TaskList>        
        **/
        DesktopTask objDesktopTask = new DesktopTask();
        
        for (FrameworkNode node : taskNode.getChildNodes())
        {
            if (node.getNodeName().equalsIgnoreCase("Document"))
            {
                Utility.ValidateAttributes(new String[]
                {
                    "Action"
                }, node);
                
                if (!objDesktopTask.SetDocument(node.getTextContent()))
                {
                    LOGGER.severe("Desktop task has invalid document: " + node.getTextContent());
                    return null;
                }
                if (node.hasAttribute("Action"))
                {
                    if (!objDesktopTask.SetAction(node.getAttribute("Action")))
                    {
                        LOGGER.severe("Desktop task has invalid Actiont: " + node.getAttribute("Action"));
                    }
                }
                else
                {
                    objDesktopTask.SetAction("Open");
                }
            }
        }
         
        if (!objDesktopTask.isValid())
        {
            objDesktopTask = null;
            LOGGER.severe("Task with ID: " + taskID + " contains an invalid Desktop Task.");
        }
        return objDesktopTask;
    }
    
    private LaunchProgramTask BuildLaunchApplicationTaskItem(String taskID, FrameworkNode taskNode)
    {
        /**
	<TaskList ID="TestLaunch">
		<TaskItem Type="LaunchProgram">
			<Application>foo.exe</Document>
			<Param>1</Param>
			<Param>2</Param>
		</TaskItem>
	</TaskList>	        **/
        LaunchProgramTask objRunProgramTask = new LaunchProgramTask();
        objRunProgramTask.setParams(GetParameters(taskNode));

        for (FrameworkNode node : taskNode.getChildNodes())
        {
            if (node.getNodeName().equalsIgnoreCase("Application"))
            {
                if (!objRunProgramTask.SetApplication(node.getTextContent()))
                {
                    LOGGER.severe("LaunchProgram task has invalid Application: " + node.getTextContent());
                    return null;
                }
            }
        }
         
        if (!objRunProgramTask.isValid())
        {
            objRunProgramTask = null;
            LOGGER.severe("Task with ID: " + taskID + " contains an invalid LaunchProgram Task.");
        }
        return objRunProgramTask;
    }

    private RandomTask BuildRandomTaskItem(String taskID, FrameworkNode taskNode)
    {
        /**
         * * Example Task
         * <TaskItem Type="Random">
         *
         * <Task>TaskID</Task>
         * <Task Weight="50">TaskID2</Task>
         * <Task>TaskID3</Task>
         * </TaskItem>
         */
        RandomTask objRandomTask = new RandomTask();

        double TotalWeight = 0;

        for (FrameworkNode node : taskNode.getChildNodes())
        {
            if (node.getNodeName().equalsIgnoreCase("Task"))
            {
                Utility.ValidateAttributes(new String[]
                {
                    "Weight"
                }, node);
                String strTaskID;
                double Weight = 0;
                if (node.hasAttribute("Weight"))
                {
                    Weight = node.getDoubleAttribute("Weight", 0);
                    if (Weight >= 100 || Weight <= 0)
                    {
                        LOGGER.severe("RandomTask [" + taskID + "] has a Task with an invalid weight: " + node.getAttribute("Weight"));
                        return null;
                    }
                    TotalWeight += Weight;
                }

                strTaskID = node.getTextContent();

                objRandomTask.AddTask(strTaskID, Weight);
            }
        }
        if (TotalWeight > 100)
        {
            LOGGER.severe("RandomTask [" + taskID + "] has a cummulative weight of > 100.");
            return null;
        }
        return objRandomTask;
    }

    /**
     * Called by a widget or menu item to go do a task
     *
     * @param TaskID - the TASK ID, that is associated with 1 or more task items
     * @return true if success, else false
     */
    public boolean PerformTask(String TaskID)
    {
        if (false == TaskExists(TaskID))
        {
            LOGGER.severe("Asked to perform a task [" + TaskID + "] that doesn't exist.");
            return false;
        }
        _TasksPerformed++;
        TaskList objTaskList = _TaskMap.get(TaskID.toUpperCase());
        
        if (null != objTaskList)
        {
            return objTaskList.PerformTasks();
        }
        LOGGER.severe("Tasklist [" + TaskID + "] came back null.");
        return false;
    }

    /**
     * A Oscar as come online and announced itself, so register it (in a map)
     *
     * @param OscarID - Unique ID
     * @param Address Where it
     * @param Port is from
     */
    public void OscarAnnouncementReceived(String OscarID, String Address, int Port,String OscarVersion)
    {
        if (null == _ClientMap)
        {
            _ClientMap = new ConcurrentHashMap<>();
        }
        OscarID = OscarID.toLowerCase();
        if (true == _ClientMap.containsKey(OscarID))
        { // already exists, check to see if is same
            Client objClient = _ClientMap.get(OscarID);
            if (0 == objClient.getAddress().compareTo(Address) && objClient.getPort() == Port)
            {
                // they are the same, just got another announcment from same Oscar as before
            }
            else
            {
                _ClientMap.remove(OscarID);
                _ClientMap.put(OscarID, new Client(Address, Port));
                if (0 == objClient.getAddress().compareTo(Address))
                { // going to assume old Oscar died, and a new one started on different port 
                    LOGGER.info("New Oscar [" + OscarID + "] Connection made [" + Address + "," + Integer.toString(Port) + "] Replacing the on on port " + Integer.toString(objClient.getPort()));
                }
                else // same ID, but from different IP address
                {
                    LOGGER.severe("New Oscar [" + OscarID + "] Connection made [" + Address + "," + Integer.toString(Port) + "].  This OscarID was already used.  Using new connection from now on.");
                }
            }
        }
        else // brand new Oscar
        {
            _ClientMap.put(OscarID, new Client(Address, Port));
            LOGGER.info("New Oscar [" + OscarID + "] Connection made [" + Address + "," + Integer.toString(Port) + "] Ocscar Version: " + OscarVersion);
            PerformOnConnectedTasks(); // Just do it every time a new connection is made, might be a bit redundant, but not too bad
        }
    }

    /**
     * Sends a packet to each and every Oscar registered
     *
     * @param sendData
     */
    protected boolean SendToAllOscars(byte sendData[])
    {
        if (null == _ClientMap || _ClientMap.isEmpty())
        {
            LOGGER.info("Marvin tried to send something to Oscar, but there are no Oscar's available.");
            return false;
        }
        ArrayList<String> BadList = null;
        Iterator<String> reader = this._ClientMap.keySet().iterator();
        while (reader.hasNext())
        {
            String key = reader.next();
            Client client = _ClientMap.get(key);
            if (null == client || false == client.send(sendData))
            {
                if (null == BadList) // if either null (should not happen or failure to send (could happen)
                {
                    BadList = new ArrayList<>(); // make a list of keys to nuke
                }
                BadList.add(key);
            }
            else
            {
                client.send(sendData); // send it again - it is UDP traffic, so not guaranteed.  Minion will worry about duplicates
            }
        }
        if (null != BadList) //something went wrong, nuke them.
        {
            for (String key : BadList)
            {
                _ClientMap.remove(key);
                LOGGER.info("Unable to send data to Oscar with ID:" + key);
            }
        }
        return true;
    }

    /**
     * *
     * Sends a datapacket to a specific Oscar
     *
     * @param OscarID - Which Oscar to send packet to
     * @param sendData - Data 2 send
     */
    protected void SendToOscar(String OscarID, byte sendData[])
    {
        if (null == OscarID)
        {
            LOGGER.severe("SendToOscar fn received NULL OscarID.");
            return;
        }
        
        OscarID = OscarID.toLowerCase();
        if (null == _ClientMap || _ClientMap.isEmpty())
        {
            LOGGER.info("Marvin tried to send something to Oscar, but there are no Oscar's available.");
            return;
        }
        if (_ClientMap.containsKey(OscarID))
        {
            Client client = _ClientMap.get(OscarID);
            if (null == client || false == client.send(sendData))
            {
                _ClientMap.remove(OscarID); // something not right with this sucker, so nuke it.
                LOGGER.info("Unable to send data to Oscar with ID:" + OscarID);
            }
            else
            { // success
                client.send(sendData); // send it again, just in case, as it is UDP - Other end needs to take care not to repeat
                LOGGER.info("Sent Packet to Oscar with ID=" + OscarID);
            }
        }
        else
        {
            LOGGER.info("Asked to send data to unknown (not yet established connection) Oscar with ID of: " + OscarID);
        }
    }

    public String CreateWatchdogTask()
    {
        String ID = "Watchdog Task";
        WatchdogTask objWatchdog = new WatchdogTask();
        TaskList objTask = new TaskList();
        objTask.AddTaskItem(objWatchdog);

        AddNewTask(ID, objTask, false, false);

        return ID;
    }

}
