package com.greybox.projectmesh.views

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.*
import com.greybox.projectmesh.R
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun SearchScreen(
    isSearching: MutableState<Boolean>,
    onHomeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        if (isSearching.value) {
            val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.wifi_loading))
            val progress by animateLottieCompositionAsState(
                composition = composition,
                iterations = LottieConstants.IterateForever
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                LottieAnimation(
                    composition = composition,
                    progress = { progress },
                    modifier = Modifier.size(200.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = stringResource(id = R.string.searching), color = Color.Black)
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = onHomeClick) {
                    Text(text = stringResource(id = R.string.cancel))
                }
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(16.dp)
            ) {
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = onHomeClick) {
                    Text(text = "Back to Home")
                }
            }
        }
    }
}
