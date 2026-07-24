package de.pmenke.webkt.example

import de.pmenke.webkt.example.components.KanbanBoard
import de.pmenke.webkt.example.components.KanbanBoard.TaskCard
import de.pmenke.webkt.example.components.NotFound
import de.pmenke.webkt.example.components.TaskEditor
import de.pmenke.webkt.example.components.TaskTable
import de.pmenke.webkt.example.repository.TaskRepository
import de.pmenke.webkt.example.repository.createBrowserTaskRepository
import de.pmenke.webkt.services.NavigatorService
import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.module

/** Application services and component factories resolved through the WebKt Koin environment. */
val appModule = module {
    single<TaskRepository> { createBrowserTaskRepository() }
    single { NavigatorService() }

    factoryOf(::KanbanBoard)
    factoryOf(::TaskCard)
    factoryOf(::TaskEditor)
    factoryOf(::TaskTable)
    factoryOf(::NotFound)
}
