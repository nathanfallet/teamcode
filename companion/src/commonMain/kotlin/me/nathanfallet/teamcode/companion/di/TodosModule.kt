package me.nathanfallet.teamcode.companion.di

import me.nathanfallet.teamcode.companion.domain.repositories.TodoRepository
import me.nathanfallet.teamcode.companion.domain.usecases.todos.FollowTodoUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.todos.ReadTodoUseCase
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteTodoRepository
import org.koin.dsl.module

/** Session todos (`todos.v1`): the Todo view of a session's panel and its counter, kept current by the feed (`todos.live.v1`). */
val todosModule = module {
    // Bound by its class: the demo module binds the interface to a router that keeps the demo account's lists in memory.
    single { RemoteTodoRepository(get()) }
    single<TodoRepository> { get<RemoteTodoRepository>() }
    single { FollowTodoUseCase(get(), get(), get(), get()) }
    single { ReadTodoUseCase(get(), get(), get()) }
}
