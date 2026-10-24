package me.nathanfallet.teamcode.companion.di

import me.nathanfallet.teamcode.companion.domain.repositories.GitHubLinkRepository
import me.nathanfallet.teamcode.companion.domain.usecases.github.LoadGitHubLinkUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.github.PollGitHubLinkUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.github.StartGitHubLinkUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.github.UnlinkGitHubUseCase
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteGitHubLinkRepository
import me.nathanfallet.teamcode.companion.presentation.navigation.GitHubLinkRoute
import me.nathanfallet.teamcode.companion.presentation.viewmodels.GitHubLinkViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** The member's GitHub link: which account is linked and linking one (`teamcode github link` in the app). */
val githubModule = module {
    single<GitHubLinkRepository> { RemoteGitHubLinkRepository(get(), get()) }
    single { LoadGitHubLinkUseCase(get(), get(), get()) }
    single { StartGitHubLinkUseCase(get(), get()) }
    single { PollGitHubLinkUseCase(get(), get()) }
    single { UnlinkGitHubUseCase(get(), get()) }
    viewModel { (route: GitHubLinkRoute) -> GitHubLinkViewModel(route, get(), get(), get(), get()) }
}
