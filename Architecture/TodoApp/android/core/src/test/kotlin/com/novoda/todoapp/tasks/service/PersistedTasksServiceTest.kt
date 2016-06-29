package com.novoda.todoapp.tasks.service

import com.google.common.collect.ImmutableList
import com.novoda.data.SyncState
import com.novoda.data.SyncedData
import com.novoda.event.Event
import com.novoda.todoapp.task.data.model.Id
import com.novoda.todoapp.task.data.model.Task
import com.novoda.todoapp.tasks.NoEmptyTasksPredicate
import com.novoda.todoapp.tasks.data.TasksDataFreshnessChecker
import com.novoda.todoapp.tasks.data.model.Tasks
import com.novoda.todoapp.tasks.data.source.LocalTasksDataSource
import com.novoda.todoapp.tasks.data.source.RemoteTasksDataSource
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.*
import rx.Observable
import rx.observers.TestObserver
import rx.subjects.BehaviorSubject
import kotlin.test.assertTrue

class PersistedTasksServiceTest {

    val TEST_TIME = 123L

    var taskRemoteDataSubject: BehaviorSubject<List<Task>> = BehaviorSubject.create()
    var taskLocalDataSubject: BehaviorSubject<Tasks> = BehaviorSubject.create()

    var remoteDataSource = Mockito.mock(RemoteTasksDataSource::class.java)
    var localDataSource = Mockito.mock(LocalTasksDataSource::class.java)
    var freshnessChecker = Mockito.mock(TasksDataFreshnessChecker::class.java)
    var clock = Mockito.mock(Clock::class.java)

    var service: TasksService = PersistedTasksService(localDataSource, remoteDataSource, freshnessChecker, clock)

    @Before
    fun setUp() {
        setUpService()
        service = PersistedTasksService(localDataSource, remoteDataSource, freshnessChecker, clock)
        Mockito.doAnswer {
            Observable.just(it.arguments[0])
        }.`when`(localDataSource).saveTasks(any())
        Mockito.doAnswer {
            Observable.just(it.arguments[0])
        }.`when`(localDataSource).saveTask(any())
        Mockito.doAnswer {
            Observable.just(it.arguments[0])
        }.`when`(remoteDataSource).saveTask(any())
        `when`(clock.timeInMillis()).thenReturn(TEST_TIME)
    }

    @Test
    fun given_TheLocalDataHasTasksAndTasksAreExpired_on_GetTasks_it_ShouldSaveTasksFromTheRemoteInTheLocalData() {
        val remoteTasks = sampleRemoteTasks()
        val localTasks = sampleLocalTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onNext(remoteTasks)
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onNext(localTasks)
        taskLocalDataSubject.onCompleted()
        `when`(freshnessChecker.isFresh(localTasks)).thenReturn(false)

        service.getTasksEvent().subscribe(testObserver)

        verify(localDataSource).saveTasks(asSyncedTasks(remoteTasks))
    }


    @Test
    fun given_TheLocalDataIsEmpty_on_GetTasksEvents_it_ShouldReturnTasksFromTheRemote() {
        val tasks = sampleRemoteTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onNext(tasks)
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onCompleted()

        service.getTasksEvent().subscribe(testObserver)

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                loadingEventWith(asSyncedTasks(tasks)),
                idleEventWith(asSyncedTasks(tasks))
        ))
    }

    @Test
    fun given_TheLocalDataHasTasksAndTasksAreFresh_on_GetTasksEvents_it_ShouldReturnTasksFromTheLocalData() {
        val remoteTasks = sampleRemoteTasks()
        val localTasks = sampleLocalTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onNext(remoteTasks)
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onNext(localTasks)
        taskLocalDataSubject.onCompleted()
        `when`(freshnessChecker.isFresh(localTasks)).thenReturn(true)

        service.getTasksEvent().subscribe(testObserver)

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                loadingEventWith(localTasks),
                idleEventWith(localTasks)
        ))
    }

    @Test
    fun given_TheLocalDataHasTasksAndTasksAreExpired_on_GetTasksEvents_it_ShouldReturnTasksFromTheLocalDataThenTasksFromTheRemote() {
        val remoteTasks = sampleRemoteTasks()
        val localTasks = sampleLocalTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onNext(remoteTasks)
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onNext(localTasks)
        taskLocalDataSubject.onCompleted()
        `when`(freshnessChecker.isFresh(localTasks)).thenReturn(false)

        service.getTasksEvent().subscribe(testObserver)

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                loadingEventWith(localTasks),
                loadingEventWith(asSyncedTasks(remoteTasks)),
                idleEventWith(asSyncedTasks(remoteTasks))
        ))
    }

    @Test
    fun given_TheLocalDataIsEmptyAndRemoteFails_on_GetTasksEvents_it_ShouldReturnError() {
        val testObserver = TestObserver<Event<Tasks>>()
        val throwable = Throwable()
        taskRemoteDataSubject.onError(throwable)
        taskLocalDataSubject.onCompleted()

        service.getTasksEvent().subscribe(testObserver)

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                errorEventWith(throwable)
        ))
    }

    @Test
    fun given_TheLocalDataIsEmptyAndRemoteIsEmpty_on_GetTasksEvents_it_ShouldReturnEmpty() {
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onCompleted()

        service.getTasksEvent().subscribe(testObserver)

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                idleEvent()
        ))
    }

    @Test
    fun given_TheLocalDataFailsAndRemoteIsEmpty_on_GetTasksEvents_it_ShouldReturnError() {
        val testObserver = TestObserver<Event<Tasks>>()
        val throwable = Throwable()
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onError(throwable)

        service.getTasksEvent().subscribe(testObserver)

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                errorEventWith(throwable)
        ))
    }

    @Test
    fun given_TheLocalDataFailsAndRemoteHasData_on_GetTasksEvents_it_ShouldReturnError() {
        val tasks = sampleRemoteTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        val throwable = Throwable()
        taskRemoteDataSubject.onNext(tasks)
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onError(throwable)

        service.getTasksEvent().subscribe(testObserver)

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                errorEventWith(throwable)
        ))
    }

    @Test
    fun given_TheLocalDataHasDataAndRemoteFails_on_GetTasksEvents_it_ShouldReturnErrorWithData() {
        val localTasks = sampleLocalTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        val throwable = Throwable()
        taskRemoteDataSubject.onError(throwable)
        taskLocalDataSubject.onNext(localTasks)
        taskLocalDataSubject.onCompleted()

        service.getTasksEvent().subscribe(testObserver)

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                loadingEventWith(localTasks),
                errorEventWith(localTasks, throwable)
        ))
    }

    @Test
    fun given_remoteDataSourceDataHasChanged_on_RefreshTasks_it_ShouldReturnNewData() {
        val tasks = sampleRemoteTasks()
        val tasksRefreshed = sampleRefreshedTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onNext(tasks)
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onCompleted()
        service.getTasksEvent().subscribe(testObserver)
        setUpService()
        taskRemoteDataSubject.onNext(tasksRefreshed)
        taskRemoteDataSubject.onCompleted()

        service.refreshTasks().call()

        testObserver.assertReceivedOnNext(
                listOf(
                        loadingEvent(),
                        loadingEventWith(asSyncedTasks(tasks)),
                        idleEventWith(asSyncedTasks(tasks)),
                        loadingEventWith(asSyncedTasks(tasks)),
                        loadingEventWith(asSyncedTasks(tasksRefreshed)),
                        idleEventWith(asSyncedTasks(tasksRefreshed))
                )
        )
    }

    @Test
    fun given_remoteDataSourceDataHasNotChanged_on_RefreshTasks_it_ShouldReturnNoAdditionalData() {
        val tasks = sampleRemoteTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onNext(tasks)
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onCompleted()
        service.getTasksEvent().subscribe(testObserver)
        setUpService()
        taskRemoteDataSubject.onNext(tasks)
        taskRemoteDataSubject.onCompleted()

        service.refreshTasks().call()

        testObserver.assertReceivedOnNext(
                listOf(
                        loadingEvent(),
                        loadingEventWith(asSyncedTasks(tasks)),
                        idleEventWith(asSyncedTasks(tasks)),
                        loadingEventWith(asSyncedTasks(tasks)),
                        idleEventWith(asSyncedTasks(tasks))
                )
        )
    }

    @Test
    fun given_remoteDataSourceDataHasChanged_on_RefreshTasks_it_ShouldPersistNewDataToLocalDatasitory() {
        val tasks = sampleRemoteTasks()
        val tasksRefreshed = sampleRefreshedTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onNext(tasks)
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onCompleted()
        service.getTasksEvent().subscribe(testObserver)
        setUpService()
        taskRemoteDataSubject.onNext(tasksRefreshed)
        taskRemoteDataSubject.onCompleted()

        service.refreshTasks().call()

        verify(localDataSource).saveTasks(asSyncedTasks(tasksRefreshed))
    }

    @Test
    fun given_ServiceHasAlreadySentData_on_RefreshTasks_it_ShouldRestartLoading() {
        val tasks = sampleRemoteTasks()
        val tasksRefreshed = sampleRefreshedTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onNext(tasks)
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onCompleted()
        service.getTasksEvent().subscribe(testObserver)
        setUpService()
        taskRemoteDataSubject.onNext(tasksRefreshed)
        taskRemoteDataSubject.onCompleted()

        service.refreshTasks().call()

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                loadingEventWith(asSyncedTasks(tasks)),
                idleEventWith(asSyncedTasks(tasks)),
                loadingEventWith(asSyncedTasks(tasks)),
                loadingEventWith(asSyncedTasks(tasksRefreshed)),
                idleEventWith(asSyncedTasks(tasksRefreshed))
        ))
    }

    @Test
    fun given_RemoteIsAcceptingUpdates_on_CompleteTask_it_ShouldSendAheadThenInSyncInfo() {
        val localTasks = sampleLocalTasks()
        val task = localTasks.all().get(0).data()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onNext(localTasks)
        taskLocalDataSubject.onCompleted()
        `when`(freshnessChecker.isFresh(localTasks)).thenReturn(true)
        `when`(clock.timeInMillis()).thenReturn(321);
        service.getTasksEvent().subscribe(testObserver)

        service.complete(task).call()

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                loadingEventWith(localTasks),
                idleEventWith(localTasks),
                idleEventWith(localTasks.save(SyncedData.from(task.complete(), SyncState.AHEAD, 321))),
                idleEventWith(localTasks.save(SyncedData.from(task.complete(), SyncState.IN_SYNC, 321)))
        ))
        assertTrue(testObserver.onCompletedEvents.isEmpty(), "Service stream should never terminate")
    }

    @Test
    fun given_ServiceHasPendingActionMoreRecentThanCurrentOne_on_CompleteTask_it_ShouldSkipTheUpdatesForCurrentAction() {
        val tasksOld = sampleLocalTasks()
        val taskOld = tasksOld.all().get(0)
        val syncedTask = SyncedData.from(taskOld.data(), taskOld.syncState(), 456)
        val updatedTasks = tasksOld.save(syncedTask)
        val updatedTask = syncedTask.data()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onNext(updatedTasks)
        taskLocalDataSubject.onCompleted()
        `when`(freshnessChecker.isFresh(updatedTasks)).thenReturn(true)
        `when`(clock.timeInMillis()).thenReturn(321);
        service.getTasksEvent().subscribe(testObserver)

        service.complete(updatedTask).call()

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                loadingEventWith(updatedTasks),
                idleEventWith(updatedTasks)
        ))
        assertTrue(testObserver.onCompletedEvents.isEmpty(), "Service stream should never terminate")
    }

    @Test
    fun given_RemoteIsFailingUpdates_on_CompleteTask_it_ShouldSendAheadThenThenMarkAsError() {
        val localTasks = sampleLocalTasks()
        val task = localTasks.all().get(0).data()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onNext(localTasks)
        taskLocalDataSubject.onCompleted()
        `when`(freshnessChecker.isFresh(localTasks)).thenReturn(true)
        `when`(clock.timeInMillis()).thenReturn(321);
        Mockito.doAnswer {
            Observable.error<Task>(Throwable())
        }.`when`(remoteDataSource).saveTask(any())
        service.getTasksEvent().subscribe(testObserver)

        service.complete(task).call()

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                loadingEventWith(localTasks),
                idleEventWith(localTasks),
                idleEventWith(localTasks.save(SyncedData.from(task.complete(), SyncState.AHEAD, 321))),
                idleEventWith(localTasks.save(SyncedData.from(task.complete(), SyncState.SYNC_ERROR, 321)))
        ))
        assertTrue(testObserver.onCompletedEvents.isEmpty(), "Service stream should never terminate")
    }

    @Test
    fun given_RemoteIsAcceptingUpdates_on_ActivateTask_it_ShouldSendAheadThenInSyncInfo() {
        val localTasks = sampleLocalCompletedTasks()
        val task = localTasks.all().get(0).data()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onNext(localTasks)
        taskLocalDataSubject.onCompleted()
        `when`(freshnessChecker.isFresh(localTasks)).thenReturn(true)
        `when`(clock.timeInMillis()).thenReturn(321);
        service.getTasksEvent().subscribe(testObserver)

        service.activate(task).call()

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                loadingEventWith(localTasks),
                idleEventWith(localTasks),
                idleEventWith(localTasks.save(SyncedData.from(task.activate(), SyncState.AHEAD, 321))),
                idleEventWith(localTasks.save(SyncedData.from(task.activate(), SyncState.IN_SYNC, 321)))
        ))
        assertTrue(testObserver.onCompletedEvents.isEmpty(), "Service stream should never terminate")
    }

    @Test
    fun given_ServiceHasPendingActionMoreRecentThanCurrentOne_on_ActivateTask_it_ShouldSkipTheUpdatesForCurrentAction() {
        val tasksOld = sampleLocalCompletedTasks()
        val taskOld = tasksOld.all().get(0)
        val syncedTask = SyncedData.from(taskOld.data(), taskOld.syncState(), 456)
        val tasks = tasksOld.save(syncedTask)
        val task = syncedTask.data()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onNext(tasks)
        taskLocalDataSubject.onCompleted()
        `when`(freshnessChecker.isFresh(tasks)).thenReturn(true)
        `when`(clock.timeInMillis()).thenReturn(321);
        service.getTasksEvent().subscribe(testObserver)

        service.activate(task).call()

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                loadingEventWith(tasks),
                idleEventWith(tasks)
        ))
        assertTrue(testObserver.onCompletedEvents.isEmpty(), "Service stream should never terminate")
    }

    @Test
    fun given_RemoteIsFailingUpdates_on_ActivateTask_it_ShouldSendAheadThenMarkAsError() {
        val localTasks = sampleLocalCompletedTasks()
        val task = localTasks.all().get(0).data()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onNext(localTasks)
        taskLocalDataSubject.onCompleted()
        `when`(freshnessChecker.isFresh(localTasks)).thenReturn(true)
        `when`(clock.timeInMillis()).thenReturn(321);
        Mockito.doAnswer {
            Observable.error<Task>(Throwable())
        }.`when`(remoteDataSource).saveTask(any())
        service.getTasksEvent().subscribe(testObserver)

        service.activate(task).call()

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                loadingEventWith(localTasks),
                idleEventWith(localTasks),
                idleEventWith(localTasks.save(SyncedData.from(task.activate(), SyncState.AHEAD, 321))),
                idleEventWith(localTasks.save(SyncedData.from(task.activate(), SyncState.SYNC_ERROR, 321)))
        ))
        assertTrue(testObserver.onCompletedEvents.isEmpty(), "Service stream should never terminate")
    }

    @Test
    fun given_RemoteIsAcceptingUpdates_on_SaveTask_it_ShouldSendAheadThenInSyncInfo() {
        val localTasks = sampleLocalCompletedTasks()
        val task = localTasks.all().get(0).data()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onNext(localTasks)
        taskLocalDataSubject.onCompleted()
        `when`(freshnessChecker.isFresh(localTasks)).thenReturn(true)
        `when`(clock.timeInMillis()).thenReturn(321);
        service.getTasksEvent().subscribe(testObserver)

        val newTask = task.toBuilder().description("NewDesc").build()
        service.save(newTask).call()

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                loadingEventWith(localTasks),
                idleEventWith(localTasks),
                idleEventWith(localTasks.save(SyncedData.from(newTask, SyncState.AHEAD, 321))),
                idleEventWith(localTasks.save(SyncedData.from(newTask, SyncState.IN_SYNC, 321)))
        ))
        assertTrue(testObserver.onCompletedEvents.isEmpty(), "Service stream should never terminate")
    }

    @Test
    fun given_ServiceHasPendingActionMoreRecentThanCurrentOne_on_SaveTask_it_ShouldSkipTheUpdatesForCurrentAction() {
        val tasksOld = sampleLocalCompletedTasks()
        val taskOld = tasksOld.all().get(0)
        val syncedTask = SyncedData.from(taskOld.data(), taskOld.syncState(), 456)
        val tasks = tasksOld.save(syncedTask)
        val task = syncedTask.data()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onNext(tasks)
        taskLocalDataSubject.onCompleted()
        `when`(freshnessChecker.isFresh(tasks)).thenReturn(true)
        `when`(clock.timeInMillis()).thenReturn(321);
        service.getTasksEvent().subscribe(testObserver)

        val newTask = task.toBuilder().description("NewDesc").build()
        service.save(newTask).call()

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                loadingEventWith(tasks),
                idleEventWith(tasks)
        ))
        assertTrue(testObserver.onCompletedEvents.isEmpty(), "Service stream should never terminate")
    }

    @Test
    fun given_RemoteIsFailingUpdates_on_SaveTask_it_ShouldSendAheadThenMarkAsError() {
        val localTasks = sampleLocalCompletedTasks()
        val task = localTasks.all().get(0).data()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onNext(localTasks)
        taskLocalDataSubject.onCompleted()
        `when`(freshnessChecker.isFresh(localTasks)).thenReturn(true)
        `when`(clock.timeInMillis()).thenReturn(321);
        Mockito.doAnswer {
            Observable.error<Task>(Throwable())
        }.`when`(remoteDataSource).saveTask(any())
        service.getTasksEvent().subscribe(testObserver)

        val newTask = task.toBuilder().description("NewDesc").build()
        service.save(newTask).call()

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                loadingEventWith(localTasks),
                idleEventWith(localTasks),
                idleEventWith(localTasks.save(SyncedData.from(newTask, SyncState.AHEAD, 321))),
                idleEventWith(localTasks.save(SyncedData.from(newTask, SyncState.SYNC_ERROR, 321)))
        ))
        assertTrue(testObserver.onCompletedEvents.isEmpty(), "Service stream should never terminate")
    }

    @Test
    fun given_TheLocalDataIsEmpty_on_GetActiveTasks_it_ShouldSaveTasksFromTheRemoteInTheLocalData() {
        val tasks = sampleRemoteTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onNext(tasks)
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onCompleted()

        service.getActiveTasksEvent().subscribe(testObserver)

        verify(localDataSource).saveTasks(asSyncedTasks(tasks))
    }

    @Test
    fun given_TheLocalDataHasTasksAndTasksAreExpired_on_GetActiveTasks_it_ShouldSaveTasksFromTheRemoteInTheLocalData() {
        val remoteTasks = sampleRemoteTasks()
        val localTasks = sampleLocalTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onNext(remoteTasks)
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onNext(localTasks)
        taskLocalDataSubject.onCompleted()
        `when`(freshnessChecker.isFresh(localTasks)).thenReturn(false)

        service.getActiveTasksEvent().subscribe(testObserver)

        verify(localDataSource).saveTasks(asSyncedTasks(remoteTasks))
    }

    @Test
    fun given_TheLocalDataIsEmpty_on_GetActiveTasksEvents_it_ShouldReturnTasksFromTheRemote() {
        val tasks = sampleRemoteTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onNext(tasks)
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onCompleted()

        service.getActiveTasksEvent().subscribe(testObserver)

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                loadingEventWith(asSyncedTasks(tasks)),
                idleEventWith(asSyncedTasks(tasks))
        ))
    }

    @Test
    fun given_TheLocalDataHasTasksAndTasksAreFresh_on_GetActiveTasksEvents_it_ShouldReturnTasksFromTheLocalData() {
        val remoteTasks = sampleRemoteTasks()
        val localTasks = sampleLocalTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onNext(remoteTasks)
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onNext(localTasks)
        taskLocalDataSubject.onCompleted()
        `when`(freshnessChecker.isFresh(localTasks)).thenReturn(true)

        service.getActiveTasksEvent().subscribe(testObserver)

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                loadingEventWith(localTasks),
                idleEventWith(localTasks)
        ))
    }

    @Test
    fun given_TheLocalDataHasTasksAndTasksAreExpired_on_GetActiveTasksEvents_it_ShouldReturnTasksFromTheLocalDataThenTasksFromTheRemote() {
        val remoteTasks = sampleRemoteTasks()
        val localTasks = sampleLocalTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onNext(remoteTasks)
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onNext(localTasks)
        taskLocalDataSubject.onCompleted()
        `when`(freshnessChecker.isFresh(localTasks)).thenReturn(false)

        service.getActiveTasksEvent().subscribe(testObserver)

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                loadingEventWith(localTasks),
                loadingEventWith(asSyncedTasks(remoteTasks)),
                idleEventWith(asSyncedTasks(remoteTasks))
        ))
    }

    @Test
    fun given_TheLocalDataIsEmptyAndRemoteFails_on_GetActiveTasksEvents_it_ShouldReturnError() {
        val testObserver = TestObserver<Event<Tasks>>()
        val throwable = Throwable()
        taskRemoteDataSubject.onError(throwable)
        taskLocalDataSubject.onCompleted()

        service.getActiveTasksEvent().subscribe(testObserver)

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                errorEventWith(throwable)
        ))
    }

    @Test
    fun given_TheLocalDataIsEmptyAndRemoteIsEmpty_on_GetActiveTasksEvents_it_ShouldReturnEmpty() {
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onCompleted()

        service.getActiveTasksEvent().subscribe(testObserver)

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                idleEvent()
        ))
    }

    @Test
    fun given_TheLocalDataFailsAndRemoteIsEmpty_on_GetActiveTasksEvents_it_ShouldReturnError() {
        val testObserver = TestObserver<Event<Tasks>>()
        val throwable = Throwable()
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onError(throwable)

        service.getActiveTasksEvent().subscribe(testObserver)

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                errorEventWith(throwable)
        ))
    }

    @Test
    fun given_TheLocalDataFailsAndRemoteHasData_on_GetActiveTasksEvents_it_ShouldReturnError() {
        val tasks = sampleRemoteTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        val throwable = Throwable()
        taskRemoteDataSubject.onNext(tasks)
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onError(throwable)

        service.getActiveTasksEvent().subscribe(testObserver)

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                errorEventWith(throwable)
        ))
    }

    @Test
    fun given_TheLocalDataHasDataAndRemoteFails_on_GetActiveTasksEvents_it_ShouldReturnErrorWithData() {
        val localTasks = sampleLocalTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        val throwable = Throwable()
        taskRemoteDataSubject.onError(throwable)
        taskLocalDataSubject.onNext(localTasks)
        taskLocalDataSubject.onCompleted()

        service.getActiveTasksEvent().subscribe(testObserver)

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                loadingEventWith(localTasks),
                errorEventWith(localTasks, throwable)
        ))
    }

    @Test
    fun given_TheTasksAreAllCompleted_on_GetActiveTasksEvents_it_ShouldReturnEmpty() {
        val tasks = sampleLocalCompletedTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onNext(tasks)
        taskLocalDataSubject.onCompleted()

        service.getActiveTasksEvent().subscribe(testObserver)

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                loadingEvent(),
                idleEvent()
        ))
    }

    @Test
    fun given_TheTasksSomeTasksAreCompleted_on_GetActiveTasksEvents_it_ShouldFilterTasks() {
        val tasks = sampleLocalSomeCompletedTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onNext(tasks)
        taskLocalDataSubject.onCompleted()

        service.getActiveTasksEvent().subscribe(testObserver)

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                loadingEventWith(sampleLocalActivatedTasks()),
                idleEventWith(sampleLocalActivatedTasks())
        ))
    }

    @Test
    fun given_TheLocalDataIsEmpty_on_GetCompletedTasks_it_ShouldSaveTasksFromTheRemoteInTheLocalData() {
        val tasks = sampleRemoteCompletedTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onNext(tasks)
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onCompleted()

        service.getCompletedTasksEvent().subscribe(testObserver)

        verify(localDataSource).saveTasks(asSyncedTasks(tasks))
    }

    @Test
    fun given_TheLocalDataHasTasksAndTasksAreExpired_on_GetCompletedTasks_it_ShouldSaveTasksFromTheRemoteInTheLocalData() {
        val remoteTasks = sampleRemoteCompletedTasks()
        val localTasks = sampleLocalCompletedTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onNext(remoteTasks)
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onNext(localTasks)
        taskLocalDataSubject.onCompleted()
        `when`(freshnessChecker.isFresh(localTasks)).thenReturn(false)

        service.getCompletedTasksEvent().subscribe(testObserver)

        verify(localDataSource).saveTasks(asSyncedTasks(remoteTasks))
    }

    @Test
    fun given_TheLocalDataIsEmpty_on_GetCompletedTasksEvents_it_ShouldReturnTasksFromTheRemote() {
        val tasks = sampleRemoteCompletedTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onNext(tasks)
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onCompleted()

        service.getCompletedTasksEvent().subscribe(testObserver)

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                loadingEventWith(asSyncedTasks(tasks)),
                idleEventWith(asSyncedTasks(tasks))
        ))
    }

    @Test
    fun given_TheLocalDataHasTasksAndTasksAreFresh_on_GetCompletedTasksEvents_it_ShouldReturnTasksFromTheLocalData() {
        val remoteTasks = sampleRemoteCompletedTasks()
        val localTasks = sampleLocalCompletedTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onNext(remoteTasks)
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onNext(localTasks)
        taskLocalDataSubject.onCompleted()
        `when`(freshnessChecker.isFresh(localTasks)).thenReturn(true)

        service.getCompletedTasksEvent().subscribe(testObserver)

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                loadingEventWith(localTasks),
                idleEventWith(localTasks)
        ))
    }

    @Test
    fun given_TheLocalDataHasTasksAndTasksAreExpired_on_GetCompletedTasksEvents_it_ShouldReturnTasksFromTheLocalDataThenTasksFromTheRemote() {
        val remoteTasks = sampleRemoteCompletedTasks()
        val localTasks = sampleLocalCompletedTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onNext(remoteTasks)
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onNext(localTasks)
        taskLocalDataSubject.onCompleted()
        `when`(freshnessChecker.isFresh(localTasks)).thenReturn(false)

        service.getCompletedTasksEvent().subscribe(testObserver)

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                loadingEventWith(localTasks),
                loadingEventWith(asSyncedTasks(remoteTasks)),
                idleEventWith(asSyncedTasks(remoteTasks))
        ))
    }

    @Test
    fun given_TheLocalDataIsEmptyAndRemoteFails_on_GetCompletedTasksEvents_it_ShouldReturnError() {
        val testObserver = TestObserver<Event<Tasks>>()
        val throwable = Throwable()
        taskRemoteDataSubject.onError(throwable)
        taskLocalDataSubject.onCompleted()

        service.getCompletedTasksEvent().subscribe(testObserver)

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                errorEventWith(throwable)
        ))
    }

    @Test
    fun given_TheLocalDataIsEmptyAndRemoteIsEmpty_on_GetCompletedTasksEvents_it_ShouldReturnEmpty() {
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onCompleted()

        service.getCompletedTasksEvent().subscribe(testObserver)

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                idleEvent()
        ))
    }

    @Test
    fun given_TheLocalDataFailsAndRemoteIsEmpty_on_GetCompletedTasksEvents_it_ShouldReturnError() {
        val testObserver = TestObserver<Event<Tasks>>()
        val throwable = Throwable()
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onError(throwable)

        service.getCompletedTasksEvent().subscribe(testObserver)

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                errorEventWith(throwable)
        ))
    }

    @Test
    fun given_TheLocalDataFailsAndRemoteHasData_on_GetCompletedTasksEvents_it_ShouldReturnError() {
        val tasks = sampleRemoteCompletedTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        val throwable = Throwable()
        taskRemoteDataSubject.onNext(tasks)
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onError(throwable)

        service.getCompletedTasksEvent().subscribe(testObserver)

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                errorEventWith(throwable)
        ))
    }

    @Test
    fun given_TheLocalDataHasDataAndRemoteFails_on_GetCompletedTasksEvents_it_ShouldReturnErrorWithData() {
        val localTasks = sampleLocalCompletedTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        val throwable = Throwable()
        taskRemoteDataSubject.onError(throwable)
        taskLocalDataSubject.onNext(localTasks)
        taskLocalDataSubject.onCompleted()

        service.getCompletedTasksEvent().subscribe(testObserver)

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                loadingEventWith(localTasks),
                errorEventWith(localTasks, throwable)
        ))
    }

    @Test
    fun given_TheTasksAreAllActivated_on_GetCompletedTasksEvents_it_ShouldReturnEmpty() {
        val tasks = sampleLocalActivatedTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onNext(tasks)
        taskLocalDataSubject.onCompleted()

        service.getCompletedTasksEvent().subscribe(testObserver)

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                loadingEvent(),
                idleEvent()
        ))
    }

    @Test
    fun given_TheTasksSomeTasksAreCompleted_on_GetCompletedTasksEvents_it_ShouldFilterTasks() {
        val tasks = sampleLocalSomeCompletedTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onNext(tasks)
        taskLocalDataSubject.onCompleted()

        service.getCompletedTasksEvent().subscribe(testObserver)

        testObserver.assertReceivedOnNext(listOf(
                loadingEvent(),
                loadingEventWith(sampleLocalCompletedTasks()),
                idleEventWith(sampleLocalCompletedTasks())
        ))
    }

    @Test
    fun given_WeHaveTasksInTheService_on_ClearCompletedTasks_it_ShouldReturnLocallyClearedTaskFirstThenConfirm() {
        val tasks = sampleRemoteSomeCompletedTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onNext(tasks)
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onCompleted()
        service.getTasksEvent().subscribe(TestObserver<Event<Tasks>>())
        `when`(remoteDataSource.clearCompletedTasks()).thenReturn(Observable.just(sampleRemoteSomeCompletedTasksDeleted()));
        service.getTasksEvent().subscribe(testObserver)

        service.clearCompletedTasks().call();

        testObserver.assertReceivedOnNext(listOf(
                idleEventWith(asSyncedTasks(tasks)),
                loadingEventWith(sampleLocalSomeCompletedTasksDeleted()),
                loadingEventWith(asSyncedTasks(sampleRemoteSomeCompletedTasksDeleted())),
                idleEventWith(asSyncedTasks(sampleRemoteSomeCompletedTasksDeleted()))
        ))
    }

    @Test
    fun given_RemoteSourceFailsToClearCompletedTasks_on_ClearCompletedTasks_it_ShouldReturnLocallyClearedTaskFirstThenSyncError() {
        val tasks = sampleRemoteSomeCompletedTasks()
        val testObserver = TestObserver<Event<Tasks>>()
        taskRemoteDataSubject.onNext(tasks)
        taskRemoteDataSubject.onCompleted()
        taskLocalDataSubject.onCompleted()
        service.getTasksEvent().subscribe(TestObserver<Event<Tasks>>())
        `when`(remoteDataSource.clearCompletedTasks()).thenReturn(Observable.error(Throwable("Terrible things")));
        service.getTasksEvent().subscribe(testObserver)

        service.clearCompletedTasks().call();

        testObserver.assertReceivedOnNext(listOf(
                idleEventWith(asSyncedTasks(tasks)),
                loadingEventWith(sampleLocalSomeCompletedTasksDeleted()),
                idleEventWith(sampleSomeCompletedTasksDeletedFailed()).asError(SyncError())
        ))
    }

    private fun idleEventWith(tasks: Tasks?) = idleEvent().updateData(tasks)

    private fun loadingEventWith(tasks: Tasks?) = loadingEvent().updateData(tasks)

    private fun idleEvent() = Event.idle<Tasks>(noEmptyTasks())

    private fun loadingEvent() = Event.loading<Tasks>(noEmptyTasks())

    private fun errorEventWith(localTasks: Tasks, throwable: Throwable) = defaultErrorEvent(localTasks, throwable).toBuilder().dataValidator(noEmptyTasks()).build()

    private fun errorEventWith(throwable: Throwable) = defaultErrorEvent(throwable).toBuilder().dataValidator(noEmptyTasks()).build()

    private fun defaultErrorEvent(localTasks: Tasks, throwable: Throwable) = defaultErrorEvent(throwable).updateData(localTasks)

    private fun defaultErrorEvent(throwable: Throwable) = Event.error<Tasks>(throwable)


    private fun sampleRefreshedTasks() = listOf(
            Task.builder().id(Id.from("42")).title("Foo").build(),
            Task.builder().id(Id.from("24")).title("Bar").build(),
            Task.builder().id(Id.from("424")).title("New").build()
    )

    private fun sampleRemoteTasks() = listOf(
            Task.builder().id(Id.from("42")).title("Foo").build(),
            Task.builder().id(Id.from("24")).title("Bar").build()
    )

    private fun sampleRemoteSomeCompletedTasks() = listOf(
            Task.builder().id(Id.from("24")).title("Bar").isCompleted(true).build(),
            Task.builder().id(Id.from("42")).title("Foo").build(),
            Task.builder().id(Id.from("12")).title("Whizz").build(),
            Task.builder().id(Id.from("424")).title("New").isCompleted(true).build()
    )

    private fun sampleLocalSomeCompletedTasksDeleted() = Tasks.from(ImmutableList.copyOf(listOf(
            SyncedData.from(Task.builder().id(Id.from("24")).title("Bar").isCompleted(true).build(), SyncState.DELETED_LOCALLY, TEST_TIME),
            SyncedData.from(Task.builder().id(Id.from("42")).title("Foo").build(), SyncState.AHEAD, TEST_TIME),
            SyncedData.from(Task.builder().id(Id.from("12")).title("Whizz").build(), SyncState.AHEAD, TEST_TIME),
            SyncedData.from(Task.builder().id(Id.from("424")).title("New").isCompleted(true).build(), SyncState.DELETED_LOCALLY, TEST_TIME)
    )))

    private fun sampleSomeCompletedTasksDeletedFailed() = Tasks.from(ImmutableList.copyOf(listOf(
            SyncedData.from(Task.builder().id(Id.from("24")).title("Bar").isCompleted(true).build(), SyncState.SYNC_ERROR, TEST_TIME),
            SyncedData.from(Task.builder().id(Id.from("42")).title("Foo").build(), SyncState.SYNC_ERROR, TEST_TIME),
            SyncedData.from(Task.builder().id(Id.from("12")).title("Whizz").build(), SyncState.SYNC_ERROR, TEST_TIME),
            SyncedData.from(Task.builder().id(Id.from("424")).title("New").isCompleted(true).build(), SyncState.SYNC_ERROR, TEST_TIME)
    )))

    private fun sampleRemoteSomeCompletedTasksDeleted() = listOf(
            Task.builder().id(Id.from("42")).title("Foo").build()
    )

    private fun sampleRemoteCompletedTasks() = listOf(
            Task.builder().id(Id.from("42")).title("Foo").isCompleted(true).build(),
            Task.builder().id(Id.from("24")).title("Bar").isCompleted(true).build()
    )

    private fun sampleLocalTasks() = asSyncedTasks(listOf(
            Task.builder().id(Id.from("24")).title("Bar").build()
    ))

    private fun sampleLocalCompletedTasks() = asSyncedTasks(listOf(
            Task.builder().id(Id.from("42")).title("Foo").isCompleted(true).build()
    ))

    private fun sampleLocalActivatedTasks() = asSyncedTasks(listOf(
            Task.builder().id(Id.from("24")).title("Bar").build()
    ))

    private fun sampleLocalSomeCompletedTasks() = asSyncedTasks(listOf(
            Task.builder().id(Id.from("42")).title("Foo").isCompleted(true).build(),
            Task.builder().id(Id.from("24")).title("Bar").build()
    ))

    private fun asSyncedTasks(remoteTasks: List<Task>) = Tasks.asSynced(remoteTasks, TEST_TIME)

    private fun noEmptyTasks() = NoEmptyTasksPredicate()

    private fun setUpService() {
        taskRemoteDataSubject = BehaviorSubject.create()
        taskLocalDataSubject = BehaviorSubject.create()
        val tasksApiReplay = taskRemoteDataSubject.replay()
        val tasksLocalDataReplay = taskLocalDataSubject.replay()
        tasksApiReplay.connect()
        tasksLocalDataReplay.connect()
        `when`(remoteDataSource.getTasks()).thenReturn(tasksApiReplay)
        `when`(localDataSource.getTasks()).thenReturn(tasksLocalDataReplay)
    }
}
