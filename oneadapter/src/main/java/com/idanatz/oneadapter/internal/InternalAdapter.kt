package com.idanatz.oneadapter.internal

import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import androidx.recyclerview.widget.RecyclerView
import com.idanatz.oneadapter.external.event_hooks.SwipeEventHook
import com.idanatz.oneadapter.external.holders.EmptyIndicator
import com.idanatz.oneadapter.external.holders.LoadingIndicator
import com.idanatz.oneadapter.external.interfaces.Diffable
import com.idanatz.oneadapter.external.modules.*
import com.idanatz.oneadapter.internal.animations.AnimationPositionHandler
import com.idanatz.oneadapter.internal.diffing.OneDiffUtil
import com.idanatz.oneadapter.internal.holders.*
import com.idanatz.oneadapter.internal.holders.OneViewHolder
import com.idanatz.oneadapter.internal.holders_creators.ViewHolderCreator
import com.idanatz.oneadapter.internal.holders_creators.ViewHolderCreatorsStore
import com.idanatz.oneadapter.internal.interfaces.DiffUtilCallback
import com.idanatz.oneadapter.internal.paging.EndlessScrollListener
import com.idanatz.oneadapter.internal.paging.LoadMoreObserver
import com.idanatz.oneadapter.internal.selection.*
import com.idanatz.oneadapter.internal.swiping.OneItemTouchHelper
import com.idanatz.oneadapter.internal.threading.IdentifiableFuture
import com.idanatz.oneadapter.internal.threading.OneSingleThreadPoolExecutor
import com.idanatz.oneadapter.internal.utils.Logger
import com.idanatz.oneadapter.internal.utils.extensions.createMutableCopyAndApply
import com.idanatz.oneadapter.internal.utils.extensions.isClassExists
import com.idanatz.oneadapter.internal.utils.extensions.removeAllItems
import com.idanatz.oneadapter.internal.utils.extractGenericClass
import com.idanatz.oneadapter.internal.validator.Validator
import java.lang.IllegalStateException

private const val UPDATE_DATA_DELAY_MILLIS = 100L

@Suppress("UNCHECKED_CAST", "NAME_SHADOWING")
internal class InternalAdapter(val recyclerView: RecyclerView) : RecyclerView.Adapter<OneViewHolder<Diffable>>(),
        LoadMoreObserver, SelectionObserver, ItemSelectionActions {

    private val context
        get() = recyclerView.context

    internal val modules = Modules()
    internal var data: MutableList<Diffable> = mutableListOf()
    internal val holderVisibilityResolver: HolderVisibilityResolver = HolderVisibilityResolver(this)

    private val viewHolderCreatorsStore = ViewHolderCreatorsStore()
    private val animationPositionHandler = AnimationPositionHandler()
    private val logger = Logger(this)

    // Paging
    private var endlessScrollListener: EndlessScrollListener? = null

    // Item Selection
    private var oneSelectionHandler: OneSelectionHandler? = null

    // Threads Executors
    private val backgroundExecutor = OneSingleThreadPoolExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())

    // Diffing
    private var updateDataInvocationNum = 0
    private var currentSetItemFuture: IdentifiableFuture<*>? = null
    private val listUpdateCallback = object : ListUpdateCallback {
        override fun onInserted(position: Int, count: Int) {
            logger.logd { "onInserted -> position: $position, count: $count" }
            notifyItemRangeInserted(position, count)
        }
        override fun onRemoved(position: Int, count: Int) {
            logger.logd { "onRemoved -> position: $position, count: $count" }
            notifyItemRangeRemoved(position, count)
        }
        override fun onMoved(fromPosition: Int, toPosition: Int) {
            logger.logd { "onRemoved -> fromPosition: $fromPosition, toPosition: $toPosition" }
            notifyItemMoved(fromPosition, toPosition)
        }
        override fun onChanged(position: Int, count: Int, payload: Any?) {
            logger.logd { "onChanged -> position: $position, count: $count, payload: $payload" }
            notifyItemRangeChanged(position, count, payload)
        }
    }

    init {
        setHasStableIds(true)

        recyclerView.apply {
            adapter = this@InternalAdapter
            OneItemTouchHelper().attachToRecyclerView(this)
        }
    }

    //region Traditional Adapter Overrides
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OneViewHolder<Diffable> {
        val oneViewHolder = viewHolderCreatorsStore.getCreator(viewType)?.create(parent)
        oneViewHolder?.onCreateViewHolder() ?: throw RuntimeException("OneViewHolder creation failed")
        logger.logd { "onCreateViewHolder -> classDataType: ${viewHolderCreatorsStore.getClassDataType(viewType)}" }
        return oneViewHolder
    }

    override fun onBindViewHolder(holder: OneViewHolder<Diffable>, position: Int) {
        val model = data[position]
		val metadata = Metadata(
				position = position,
				animationMetadata = object : AnimationMetadata {
					override val isAnimating: Boolean = if (holder.firstBindAnimation != null) animationPositionHandler.shouldAnimateBind(holder.itemViewType, position) else false
				},
				selectionMetadata = object : SelectionMetadata {
					override val isSelected: Boolean = isPositionSelected(position)
				}
		)
		logger.logd { "onBindViewHolder -> holder: $holder, model: $model, metadata: $metadata" }
		holder.onBindViewHolder(model, metadata)
    }

    private fun onBindSelection(holder: OneViewHolder<Diffable>, position: Int, selected: Boolean) {
        val model = data[position]
        logger.logd { "onBindSelection -> holder: $holder, position: $position, model: $model, selected: $selected" }
        holder.onBindSelection(model, selected)
    }

    override fun getItemCount() = data.size

    override fun getItemId(position: Int): Long  {
        val item = data[position]
        // javaClass is used for lettings different Diffable models share the same unique identifier
        return item.javaClass.name.hashCode() + item.uniqueIdentifier
    }

    override fun getItemViewType(position: Int) = viewHolderCreatorsStore.getCreatorUniqueIndex(data[position].javaClass)

    fun getItemViewTypeFromClass(clazz: Class<*>): Int {
        Validator.validateModelClassIsDiffable(clazz)
        return viewHolderCreatorsStore.getCreatorUniqueIndex(clazz as Class<Diffable>)
    }

    override fun onViewRecycled(holder: OneViewHolder<Diffable>) {
        super.onViewRecycled(holder)
        holder.onUnbind(holder.model)
    }
    //endregion

    fun updateData(incomingData: MutableList<Diffable>) {
        val currentUpdateDataInvocationNum = ++updateDataInvocationNum
        logger.logd { "updateData -> diffing request (#$currentUpdateDataInvocationNum) with incomingData: $incomingData" }

        // handle the fast and simple cases where no diffing is required
        when {
            data.isEmpty() -> {
                Validator.validateItemsAgainstRegisteredModules(modules.itemModules, incomingData)
                uiHandler.post {
                    logger.logd { "updateData -> no diffing required, inserting all incoming data" }
                    data = incomingData
                    listUpdateCallback.onInserted(0, incomingData.size)
                }
                return
            }
            incomingData.isEmpty() && data.contains(EmptyIndicator) -> {
                uiHandler.post {
                    logger.logd { "updateData -> no diffing required, refreshing EmptyModule" }
                    listUpdateCallback.onChanged(0, 1, null)
                }
                return
            }
        }

        // cancel the last (maybe running) diffing executor
        if (currentSetItemFuture?.isDone == false) {
            logger.logd { "updateData -> canceling old diffing executor (#${currentSetItemFuture?.id})" }
            currentSetItemFuture?.cancel(true)
        }

        currentSetItemFuture = backgroundExecutor.submit(currentUpdateDataInvocationNum, Runnable {
            logger.logd { "updateData -> executing on background (#$currentUpdateDataInvocationNum)" }

            Validator.validateItemsAgainstRegisteredModules(modules.itemModules, incomingData)

            // modify the incomingData if needed
            when (incomingData.size) {
                0 -> {
                    animationPositionHandler.resetState()

                    if (modules.emptinessModule != null) { incomingData.add(EmptyIndicator) }
                    if (modules.pagingModule != null) { endlessScrollListener?.resetState() }
                }
                else -> {
                    if (modules.emptinessModule != null) incomingData.remove(EmptyIndicator)
                    if (modules.pagingModule != null) incomingData.remove(LoadingIndicator)
                }
            }

            // handle the diffing
            val diffResult = DiffUtil.calculateDiff(OneDiffUtil(data, incomingData))

            uiHandler.post {
                if (currentUpdateDataInvocationNum == updateDataInvocationNum) {
                    logger.logd { "updateData -> dispatching update (#$currentUpdateDataInvocationNum) with incomingData: $incomingData" }
                    data = incomingData
                    diffResult.dispatchUpdatesTo(listUpdateCallback)
                } else {
                    logger.logd { "updateData -> discarding old diffing result (#$currentUpdateDataInvocationNum)" }
                }
            }
        })
    }

    //region Item Module
    fun <M : Diffable> register(itemModule: ItemModule<M>) {
        val moduleConfig = itemModule.config
        val modelClass = extractGenericClass(itemModule.javaClass) as? Class<Diffable> ?: throw IllegalStateException("Unable to extract generic class from ItemModule")

        Validator.validateLayoutExists(context, itemModule.javaClass, moduleConfig.layoutResource)
        Validator.validateItemModuleAgainstRegisteredModules(modules.itemModules, modelClass)

        val layoutResourceId = moduleConfig.layoutResource!!

        modules.itemModules[modelClass] = itemModule
        viewHolderCreatorsStore.addCreator(modelClass, object : ViewHolderCreator<M> {
            override fun create(parent: ViewGroup): OneViewHolder<M> {
                return object : OneViewHolder<M>(
                        parent = parent,
                        layoutResourceId = layoutResourceId,
                        firstBindAnimation = moduleConfig.firstBindAnimation,
                        statesHooksMap = itemModule.states,
                        eventsHooksMap = itemModule.eventHooks
                ) {
                    override fun onCreated() = itemModule.onCreate?.invoke(viewBinder) ?: Unit
                    override fun onBind(model: M) = itemModule.onBind?.invoke(model, viewBinder, metadata) ?: Unit
                    override fun onUnbind(model: M) = itemModule.onUnbind?.invoke(model, viewBinder, metadata) ?: Unit
                    override fun onClicked(model: M) = itemModule.eventHooks.getClickEventHook()?.onClick?.invoke(model, viewBinder, metadata) ?: Unit
                    override fun onSwipe(canvas: Canvas, xAxisOffset: Float) = itemModule.eventHooks.getSwipeEventHook()?.onSwipe?.invoke(canvas, xAxisOffset, viewBinder) ?: Unit
                    override fun onSwipeComplete(model: M, swipeDirection: SwipeEventHook.SwipeDirection) = itemModule.eventHooks.getSwipeEventHook()?.onSwipeComplete?.invoke(model, viewBinder, metadata) ?: Unit
                    override fun onSelected(model: M, selected: Boolean) = itemModule.states.getSelectionState()?.onSelected?.invoke(model, selected) ?: Unit
                }
            }
        } as ViewHolderCreator<Diffable>)
    }
    //endregion

    //region Emptiness Module
    fun enableEmptiness(emptinessModule: EmptinessModule) {
        val moduleConfig = emptinessModule.config
        val modelClass = EmptyIndicator.javaClass as Class<Diffable>

        Validator.validateLayoutExists(context, emptinessModule.javaClass, moduleConfig.layoutResource)

        val layoutResourceId = moduleConfig.layoutResource!!

        modules.emptinessModule = emptinessModule
        viewHolderCreatorsStore.addCreator(modelClass, object : ViewHolderCreator<Diffable> {
            override fun create(parent: ViewGroup): OneViewHolder<Diffable> {
                return object : OneViewHolder<Diffable>(
                        parent = parent,
                        layoutResourceId = layoutResourceId,
                        firstBindAnimation = moduleConfig.firstBindAnimation
                ) {
                    override fun onCreated() = emptinessModule.onCreate?.invoke(viewBinder) ?: Unit
                    override fun onBind(model: Diffable) = emptinessModule.onBind?.invoke(viewBinder, metadata) ?: Unit
                    override fun onUnbind(model: Diffable) = emptinessModule.onUnbind?.invoke(viewBinder, metadata) ?: Unit
                    override fun onClicked(model: Diffable) {}
                    override fun onSwipe(canvas: Canvas, xAxisOffset: Float) {}
                    override fun onSwipeComplete(model: Diffable, swipeDirection: SwipeEventHook.SwipeDirection) {}
                    override fun onSelected(model: Diffable, selected: Boolean) {}
                }
            }
        })
        configureEmptinessModule()
    }

    private fun configureEmptinessModule() {
        // in case emptiness module is configured, add empty indicator item
        if (data.isEmpty() && modules.emptinessModule != null) {
            data.add(0, EmptyIndicator)
        }
    }
    //endregion

    //region Paging Module
    fun enablePaging(pagingModule: PagingModule) {
        val moduleConfig = pagingModule.config
        val modelClass = LoadingIndicator.javaClass as Class<Diffable>

        Validator.validateLayoutExists(context, pagingModule.javaClass, moduleConfig.layoutResource)

        val layoutResourceId = moduleConfig.layoutResource!!

        modules.pagingModule = pagingModule
        viewHolderCreatorsStore.addCreator(modelClass, object : ViewHolderCreator<Diffable> {
            override fun create(parent: ViewGroup): OneViewHolder<Diffable> {
                return object : OneViewHolder<Diffable>(
                        parent = parent,
                        layoutResourceId = layoutResourceId,
                        firstBindAnimation = moduleConfig.firstBindAnimation
                ) {
                    override fun onCreated() = pagingModule.onCreate?.invoke(viewBinder) ?: Unit
                    override fun onBind(model: Diffable) = pagingModule.onBind?.invoke(viewBinder, metadata) ?: Unit
                    override fun onUnbind(model: Diffable) = pagingModule.onUnbind?.invoke(viewBinder, metadata) ?: Unit
                    override fun onClicked(model: Diffable) {}
                    override fun onSwipe(canvas: Canvas, xAxisOffset: Float) {}
                    override fun onSwipeComplete(model: Diffable, swipeDirection: SwipeEventHook.SwipeDirection) {}
                    override fun onSelected(model: Diffable, selected: Boolean) {}
                }
            }
        })

        recyclerView.layoutManager?.let { layoutManager ->
            endlessScrollListener = EndlessScrollListener(
                    layoutManager = layoutManager,
                    visibleThreshold = moduleConfig.visibleThreshold,
                    includeEmptyState = modules.emptinessModule != null,
                    loadMoreObserver = this@InternalAdapter,
                    logger = logger
            ).also { recyclerView.addOnScrollListener(it) }
        }
    }

    override fun onLoadingStateChanged(loading: Boolean) {
        if (loading && !data.isClassExists(LoadingIndicator.javaClass)) {
            data.add(data.size, LoadingIndicator)

            // post it to the UI handler because the recycler crashes when calling notify from an onScroll callback
            uiHandler.post { notifyItemInserted(data.size) }
        }
    }

    override fun onLoadMore(currentPage: Int) {
        modules.pagingModule?.onLoadMore?.invoke(currentPage)
    }
    //endregion

    //region Selection Module
    fun enableSelection(itemSelectionModule: ItemSelectionModule) {
        itemSelectionModule.actions = this
        modules.itemSelectionModule = itemSelectionModule
        oneSelectionHandler = OneSelectionHandler(itemSelectionModule, recyclerView).also { it.observer = this }
    }

    override fun onItemStateChanged(holder: OneViewHolder<Diffable>, position: Int, selected: Boolean) {
        onBindSelection(holder, position, selected)
    }

    override fun onSelectionStarted() {
        modules.itemSelectionModule?.onStartSelection?.invoke()
    }

    override fun onSelectionEnded() {
        modules.itemSelectionModule?.onEndSelection?.invoke()
    }

    override fun onSelectionUpdated(selectedCount: Int) {
        modules.itemSelectionModule?.onUpdateSelection?.invoke(selectedCount)
    }

    override fun startSelection() {
        oneSelectionHandler?.startSelection()
    }

    override fun clearSelection(): Boolean {
        return oneSelectionHandler?.clearSelection() ?: false
    }

    override fun getSelectedPositions(): List<Int> {
        return oneSelectionHandler?.getSelectedPositions() ?: emptyList()
    }

    override fun getSelectedItems(): List<Diffable> {
        return oneSelectionHandler?.getSelectedPositions()?.map { position -> data[position] } ?: emptyList()
    }

    override fun isSelectionActive(): Boolean {
        return oneSelectionHandler?.inSelectionActive() ?: false
    }

    override fun isPositionSelected(position: Int): Boolean {
        return oneSelectionHandler?.isPositionSelected(position) ?: false
    }

    override fun removeSelectedItems() {
        val modifiedData = data.createMutableCopyAndApply { removeAllItems(getSelectedItems()) }
        updateData(modifiedData)

        uiHandler.postDelayed({ clearSelection() }, UPDATE_DATA_DELAY_MILLIS)
    }
    //endregion

    //region RecyclerView
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        endlessScrollListener?.let { recyclerView.removeOnScrollListener(it) }
    }
    //endregion
}