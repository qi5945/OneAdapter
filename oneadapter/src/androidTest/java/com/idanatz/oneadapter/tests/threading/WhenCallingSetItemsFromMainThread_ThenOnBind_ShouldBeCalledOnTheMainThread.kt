@file:Suppress("ClassName")

package com.idanatz.oneadapter.tests.threading

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.idanatz.oneadapter.external.modules.ItemModule
import com.idanatz.oneadapter.helpers.BaseTest
import com.idanatz.oneadapter.models.TestModel
import com.idanatz.oneadapter.test.R
import org.amshove.kluent.shouldEqual
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhenCallingSetItemsFromMainThread_ThenOnBind_ShouldBeCalledOnTheMainThread : BaseTest() {

	private var currentThreadName: String? = null

	@Test
	fun test() {
		configure {
			prepareOnActivity {
				oneAdapter.attachItemModule(TestItemModule())
			}
			actOnActivity {
				oneAdapter.setItems(modelGenerator.generateModels(2))
			}
			untilAsserted {
				currentThreadName shouldEqual "main"
			}
		}
	}

	inner class TestItemModule : ItemModule<TestModel>() {
		init {
			config = modulesGenerator.generateValidItemModuleConfig(resourceId = R.layout.test_model_large)
			onBind { _, _, _ ->
				currentThreadName = Thread.currentThread().name
			}
		}
	}
}